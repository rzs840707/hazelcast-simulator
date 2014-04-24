/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hazelcast.stabilizer.coordinator;

import com.hazelcast.config.Config;
import com.hazelcast.config.XmlConfigBuilder;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.stabilizer.TestCase;
import com.hazelcast.stabilizer.Utils;
import com.hazelcast.stabilizer.agent.workerjvm.WorkerJvmSettings;
import com.hazelcast.stabilizer.tests.Failure;
import com.hazelcast.stabilizer.tests.TestSuite;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static com.hazelcast.stabilizer.Utils.createUpload;
import static com.hazelcast.stabilizer.Utils.getStablizerHome;
import static com.hazelcast.stabilizer.Utils.getVersion;
import static com.hazelcast.stabilizer.Utils.secondsToHuman;
import static com.hazelcast.stabilizer.Utils.sleepSeconds;
import static com.hazelcast.stabilizer.coordinator.CoordinatorCli.init;
import static java.lang.String.format;

public class Coordinator {

    public final static File STABILIZER_HOME = getStablizerHome();
    private final static ILogger log = Logger.getLogger(Coordinator.class.getName());

    //options.
    public boolean monitorPerformance;
    public boolean verifyEnabled = true;
    public String workerClassPath;
    public boolean cleanWorkersHome;
    public Integer testStopTimeoutMs;
    public File machinesFile;
    public TestSuite testSuite;

    //internal state.
    final BlockingQueue<Failure> failureList = new LinkedBlockingQueue<Failure>();
    protected AgentClientManager agentClientManager;
    public WorkerJvmSettings workerJvmSettings;

    private void start() throws Exception {
        agentClientManager = new AgentClientManager(this, machinesFile);
        agentClientManager.getFailures();
        new FailureMonitorThread().start();

        if (cleanWorkersHome) {
            echo("Starting cleanup workers home");
            agentClientManager.cleanWorkersHome();
            echo("Finished cleanup workers home");
        }

        byte[] uploadBytes = createUpload(workerClassPath);
        agentClientManager.initTestSuite(testSuite, uploadBytes);

        initHzConfig(workerJvmSettings);
        initClientHzConfig(workerJvmSettings);

        int agentCount = agentClientManager.getAgentCount();
        log.info(format("Worker track logging: %s", workerJvmSettings.trackLogging));
        log.info(format("Total number of agents: %s", agentCount));
        log.info(format("Total number of Hazelcast Member workers: %s", workerJvmSettings.memberWorkerCount));
        log.info(format("Total number of Hazelcast Client workers: %s", workerJvmSettings.clientWorkerCount));
        log.info(format("Total number of Hazelcast Mixed Client & Member Workers: %s", workerJvmSettings.mixedWorkerCount));

        long startMs = System.currentTimeMillis();

        runTestSuite(testSuite);

        //the coordinator needs to sleep some to make sure that it will get failures if they are there.
        log.info("Starting cool down (20 sec)");
        Utils.sleepSeconds(20);
        log.info("Finished cool down");

        long elapsedMs = System.currentTimeMillis() - startMs;
        log.info(format("Total running time: %s seconds", elapsedMs / 1000));

        if (failureList.isEmpty()) {
            log.info("-----------------------------------------------------------------------------");
            log.info("No failures have been detected!");
            log.info("-----------------------------------------------------------------------------");
            System.exit(0);
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append(failureList.size()).append(" Failures have been detected!!!\n");
            for (Failure failure : failureList) {
                sb.append("-----------------------------------------------------------------------------\n");
                sb.append(failure).append('\n');
            }
            sb.append("-----------------------------------------------------------------------------\n");
            log.severe(sb.toString());
            System.exit(1);
        }
    }

    private void initHzConfig(WorkerJvmSettings settings) throws Exception {
        int port = getPort(settings);

        StringBuffer members = new StringBuffer();
        for (String hostAddress : agentClientManager.getHostAddresses()) {
            members.append("<member>").append(hostAddress).append(":" + port).append("</member>\n");
        }

        settings.hzConfig = settings.hzConfig.replace("<!--MEMBERS-->", members);
    }

    private void initClientHzConfig(WorkerJvmSettings settings) throws Exception {
        int port = getPort(settings);

        StringBuffer members = new StringBuffer();
        for (String hostAddress : agentClientManager.getHostAddresses()) {
            members.append("<address>").append(hostAddress).append(":" + port).append("</address>\n");
        }

        settings.clientHzConfig = settings.clientHzConfig.replace("<!--MEMBERS-->", members);
    }

    private int getPort(WorkerJvmSettings settings) throws UnsupportedEncodingException {
        Config config = new XmlConfigBuilder(new ByteArrayInputStream(settings.hzConfig.getBytes("UTF-8"))).build();
        return config.getNetworkConfig().getPort();
    }

    private void runTestSuite(TestSuite testSuite) throws Exception {
        echo(format("Starting testsuite: %s", testSuite.id));
        echo(format("Tests in testsuite: %s", testSuite.size()));
        echo(format("Running time per test: %s ", secondsToHuman(testSuite.duration)));
        echo(format("Expected total testsuite time: %s", secondsToHuman(testSuite.size() * testSuite.duration)));

        //we need to make sure that before we launch, there are no workers running anymore.
        terminateWorkers();
        startWorkers(workerJvmSettings);

        for (TestCase testCase : testSuite.testCaseList) {
            TestCaseRunner runner = new TestCaseRunner(testCase, testSuite, this);
            boolean success = runner.run();
            if (!success && testSuite.failFast) {
                log.info("Aborting working due to failure");
                break;
            }

            if (!success || workerJvmSettings.refreshJvm) {
                terminateWorkers();
                startWorkers(workerJvmSettings);
            }
        }

        terminateWorkers();
    }

    void terminateWorkers() throws Exception {
        echo("Terminating workers");
        agentClientManager.terminateWorkers();
        echo("All workers have been terminated");
    }

    long startWorkers(WorkerJvmSettings masterSettings) throws Exception {
        long startMs = System.currentTimeMillis();

        int agentCount = agentClientManager.getAgentCount();
        if (masterSettings.memberWorkerCount == -1 && masterSettings.mixedWorkerCount == 0) {
            masterSettings.memberWorkerCount = agentCount;
        }

        log.info(format("Starting %s Server Worker JVM's", masterSettings.memberWorkerCount));
        log.info(format("Starting %s Client Worker JVM's", masterSettings.clientWorkerCount));
        log.info(format("Starting %s Mixed Worker JVM's", masterSettings.mixedWorkerCount));

        WorkerJvmSettings[] settingsArray = new WorkerJvmSettings[agentCount];
        for (int k = 0; k < agentCount; k++) {
            WorkerJvmSettings s = new WorkerJvmSettings(masterSettings);
            s.memberWorkerCount = 0;
            s.mixedWorkerCount = 0;
            s.clientWorkerCount = 0;
            settingsArray[k] = s;
        }

        int index = 0;
        for (int k = 0; k < masterSettings.memberWorkerCount; k++) {
            WorkerJvmSettings s = settingsArray[index % agentCount];
            s.memberWorkerCount++;
            index++;
        }
        for (int k = 0; k < masterSettings.clientWorkerCount; k++) {
            WorkerJvmSettings s = settingsArray[index % agentCount];
            s.clientWorkerCount++;
            index++;
        }
        for (int k = 0; k < masterSettings.mixedWorkerCount; k++) {
            WorkerJvmSettings s = settingsArray[index % agentCount];
            s.mixedWorkerCount++;
            index++;
        }

        agentClientManager.spawnWorkers(settingsArray);

        //give the agents some time to start up.
        log.info("Waiting for agents the start (20 seconds)");
        sleepSeconds(20);

        long durationMs = System.currentTimeMillis() - startMs;
        log.info((format("Finished starting a grand total of %s Workers JVM's after %s ms\n",
                masterSettings.totalWorkerCount(), durationMs)));

        return startMs;
    }

    private void echo(String msg) {
        agentClientManager.echo(msg);
        log.info(msg);
    }

    public static void main(String[] args) throws Exception {
        log.info("Hazelcast Stabilizer Coordinator");
        log.info(format("Version: %s", getVersion()));
        log.info(format("STABILIZER_HOME: %s", STABILIZER_HOME));

        Coordinator coordinator = new Coordinator();
        init(coordinator, args);

        try {
            coordinator.start();
            System.exit(0);
        } catch (Exception e) {
            log.severe("Failed to run testsuite", e);
            System.exit(1);
        }
    }

    private class FailureMonitorThread extends Thread {
        public FailureMonitorThread() {
            super("FailureMonitorThread");
            setDaemon(true);
        }

        public void run() {
            for (; ; ) {
                //todo: this delay should be configurable.
                Utils.sleepSeconds(1);

                List<Failure> failures = agentClientManager.getFailures();
                for (Failure failure : failures) {
                    failureList.add(failure);
                    log.severe("Remote failure detected:" + failure);
                }
            }
        }
    }
}
