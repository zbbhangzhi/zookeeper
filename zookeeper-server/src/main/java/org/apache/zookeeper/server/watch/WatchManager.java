/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.watch;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.ZooTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class manages watches. It allows watches to be associated with a string
 * and removes watchers and their watches in addition to managing triggers.
 * 存储标记了watcher注册请求 对应的ServerCnxn并触发它
 * 客户端收到zk发起的watcher事件触发通知，从这里找出对应的watcher执行回调逻辑
 */
public class WatchManager implements IWatchManager {
    private static final Logger LOG = LoggerFactory.getLogger(WatchManager.class);

    /**
     * 从数据节点路径的粒度来托管watcher
     */
    private final Map<String, Set<Watcher>> watchTable =
        new HashMap<String, Set<Watcher>>();

    /**
     * 从watcher的粒度来控制事件触发需要触发的数据节点
     */
    private final Map<Watcher, Set<String>> watch2Paths =
        new HashMap<Watcher, Set<String>>();
    
    @Override
    public synchronized int size(){
        int result = 0;
        for(Set<Watcher> watches : watchTable.values()) {
            result += watches.size();
        }
        return result;
    }

    boolean isDeadWatcher(Watcher watcher) {
        return watcher instanceof ServerCnxn && ((ServerCnxn) watcher).isStale();
    }

    @Override
    public synchronized boolean addWatch(String path, Watcher watcher) {
        if (isDeadWatcher(watcher)) {
            LOG.debug("Ignoring addWatch with closed cnxn");
            return false;
        }

        Set<Watcher> list = watchTable.get(path);
        if (list == null) {
            // don't waste memory if there are few watches on a node
            // rehash when the 4th entry is added, doubling size thereafter
            // seems like a good compromise
            list = new HashSet<Watcher>(4);
            watchTable.put(path, list);
        }
        list.add(watcher);

        Set<String> paths = watch2Paths.get(watcher);
        if (paths == null) {
            // cnxns typically have many watches, so use default cap here
            paths = new HashSet<String>();
            watch2Paths.put(watcher, paths);
        }
        return paths.add(path);
    }

    @Override
    public synchronized void removeWatcher(Watcher watcher) {
        Set<String> paths = watch2Paths.remove(watcher);
        if (paths == null) {
            return;
        }
        for (String p : paths) {
            Set<Watcher> list = watchTable.get(p);
            if (list != null) {
                list.remove(watcher);
                if (list.isEmpty()) {
                    watchTable.remove(p);
                }
            }
        }
    }

    @Override
    public WatcherOrBitSet triggerWatch(String path, EventType type) {
        return triggerWatch(path, type, null);
    }

    @Override
    public WatcherOrBitSet triggerWatch(
            String path, EventType type, WatcherOrBitSet supress) {
        //封装WatchEvent
        WatchedEvent e = new WatchedEvent(type,
                KeeperState.SyncConnected, path);
        Set<Watcher> watchers;
        synchronized (this) {
            //查找对应watcher并删除 如果没有就直接退出
            watchers = watchTable.remove(path);
            if (watchers == null || watchers.isEmpty()) {
                if (LOG.isTraceEnabled()) {
                    ZooTrace.logTraceMessage(LOG,
                            ZooTrace.EVENT_DELIVERY_TRACE_MASK,
                            "No watchers for " + path);
                }
                return null;
            }
            for (Watcher w : watchers) {
                Set<String> paths = watch2Paths.get(w);
                if (paths != null) {
                    paths.remove(path);
                }
            }
        }
        for (Watcher w : watchers) {
            if (supress != null && supress.contains(w)) {
                continue;
            }
            //触发watcher 对应ServerCnxn
            w.process(e);
        }

        switch (type) {
        case NodeCreated:
            ServerMetrics.getMetrics().NODE_CREATED_WATCHER.add(watchers.size());
            break;

        case NodeDeleted:
            ServerMetrics.getMetrics().NODE_DELETED_WATCHER.add(watchers.size());
            break;

        case NodeDataChanged:
            ServerMetrics.getMetrics().NODE_CHANGED_WATCHER.add(watchers.size());
            break;

        case NodeChildrenChanged:
            ServerMetrics.getMetrics().NODE_CHILDREN_WATCHER.add(watchers.size());
            break;
        default:
            // Other types not logged.
            break;
        }

        return new WatcherOrBitSet(watchers);
    }

    @Override
    public synchronized String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append(watch2Paths.size()).append(" connections watching ")
            .append(watchTable.size()).append(" paths\n");

        int total = 0;
        for (Set<String> paths : watch2Paths.values()) {
            total += paths.size();
        }
        sb.append("Total watches:").append(total);

        return sb.toString();
    }

    @Override
    public synchronized void dumpWatches(PrintWriter pwriter, boolean byPath) {
        if (byPath) {
            for (Entry<String, Set<Watcher>> e : watchTable.entrySet()) {
                pwriter.println(e.getKey());
                for (Watcher w : e.getValue()) {
                    pwriter.print("\t0x");
                    pwriter.print(Long.toHexString(((ServerCnxn)w).getSessionId()));
                    pwriter.print("\n");
                }
            }
        } else {
            for (Entry<Watcher, Set<String>> e : watch2Paths.entrySet()) {
                pwriter.print("0x");
                pwriter.println(Long.toHexString(((ServerCnxn)e.getKey()).getSessionId()));
                for (String path : e.getValue()) {
                    pwriter.print("\t");
                    pwriter.println(path);
                }
            }
        }
    }

    @Override
    public synchronized boolean containsWatcher(String path, Watcher watcher) {
        Set<String> paths = watch2Paths.get(watcher);
        if (paths == null || !paths.contains(path)) {
            return false;
        }
        return true;
    }

    @Override
    public synchronized boolean removeWatcher(String path, Watcher watcher) {
        Set<String> paths = watch2Paths.get(watcher);
        if (paths == null || !paths.remove(path)) {
            return false;
        }

        Set<Watcher> list = watchTable.get(path);
        if (list == null || !list.remove(watcher)) {
            return false;
        }

        if (list.isEmpty()) {
            watchTable.remove(path);
        }

        return true;
    }

    @Override
    public synchronized WatchesReport getWatches() {
        Map<Long, Set<String>> id2paths = new HashMap<Long, Set<String>>();
        for (Entry<Watcher, Set<String>> e: watch2Paths.entrySet()) {
            Long id = ((ServerCnxn) e.getKey()).getSessionId();
            Set<String> paths = new HashSet<String>(e.getValue());
            id2paths.put(id, paths);
        }
        return new WatchesReport(id2paths);
    }

    @Override
    public synchronized WatchesPathReport getWatchesByPath() {
        Map<String, Set<Long>> path2ids = new HashMap<String, Set<Long>>();
        for (Entry<String, Set<Watcher>> e : watchTable.entrySet()) {
            Set<Long> ids = new HashSet<Long>(e.getValue().size());
            path2ids.put(e.getKey(), ids);
            for (Watcher watcher : e.getValue()) {
                ids.add(((ServerCnxn) watcher).getSessionId());
            }
        }
        return new WatchesPathReport(path2ids);
    }

    @Override
    public synchronized WatchesSummary getWatchesSummary() {
        int totalWatches = 0;
        for (Set<String> paths : watch2Paths.values()) {
            totalWatches += paths.size();
        }
        return new WatchesSummary (watch2Paths.size(), watchTable.size(),
                                   totalWatches);
    }

    @Override
    public void shutdown() { /* do nothing */ }

}
