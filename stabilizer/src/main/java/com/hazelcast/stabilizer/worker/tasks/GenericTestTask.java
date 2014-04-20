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
package com.hazelcast.stabilizer.worker.tasks;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastInstanceAware;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.stabilizer.tests.Test;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;

import static java.lang.String.format;

public class GenericTestTask implements Callable, Serializable, HazelcastInstanceAware {

    private final static ILogger log = Logger.getLogger(GenericTestTask.class);

    private transient HazelcastInstance hz;
    private final String methodName;

    public GenericTestTask(String methodName) {
        this.methodName = methodName;
    }

    @Override
    public Object call() throws Exception {
        try {
            log.info("Calling test." + methodName + "()");

            Test test = (Test) hz.getUserContext().get(Test.TEST_INSTANCE);
            if (test == null) {
                throw new IllegalStateException("No test found for method " + methodName + "()");
            }

            Method method = test.getClass().getMethod(methodName);
            Object o = method.invoke(test);
            log.info("Finished calling test." + methodName + "()");
            return o;
        } catch (Exception e) {
            log.severe(format("Failed to execute test.%s()", methodName), e);
            throw e;
        }
    }

    @Override
    public void setHazelcastInstance(HazelcastInstance hz) {
        this.hz = hz;
    }
}