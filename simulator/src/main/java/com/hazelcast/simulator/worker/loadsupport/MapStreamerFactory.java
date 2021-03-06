package com.hazelcast.simulator.worker.loadsupport;

import com.hazelcast.core.IMap;
import com.hazelcast.instance.BuildInfo;
import com.hazelcast.instance.BuildInfoProvider;

import java.util.concurrent.atomic.AtomicBoolean;

import static com.hazelcast.simulator.utils.VersionUtils.isMinVersion;

public final class MapStreamerFactory {

    private static final AtomicBoolean CREATE_ASYNC;

    static {
        boolean createAsync = false;
        try {
            BuildInfo buildInfo = BuildInfoProvider.getBuildInfo();
            if (isMinVersion(buildInfo.getVersion(), "3.5")) {
                createAsync = true;
            }
        } finally {
            CREATE_ASYNC = new AtomicBoolean(createAsync);
        }
    }

    private MapStreamerFactory() {
    }

    public static <K, V> MapStreamer<K, V> getInstance(IMap<K, V> map) {
        if (CREATE_ASYNC.get()) {
            return new AsyncMapStreamer<K, V>(map);
        }
        return new SyncMapStreamer<K, V>(map);
    }

    static void enforceAsync(boolean enforceAsync) {
        CREATE_ASYNC.set(enforceAsync);
    }
}
