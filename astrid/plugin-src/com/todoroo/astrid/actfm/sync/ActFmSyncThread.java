package com.todoroo.astrid.actfm.sync;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import com.todoroo.andlib.service.Autowired;
import com.todoroo.andlib.service.DependencyInjectionService;
import com.todoroo.andlib.utility.Pair;
import com.todoroo.astrid.actfm.sync.messages.BriefMe;
import com.todoroo.astrid.actfm.sync.messages.ChangesHappened;
import com.todoroo.astrid.actfm.sync.messages.ClientToServerMessage;
import com.todoroo.astrid.dao.TagDataDao;
import com.todoroo.astrid.dao.TagOutstandingDao;
import com.todoroo.astrid.dao.TaskDao;
import com.todoroo.astrid.dao.TaskOutstandingDao;
import com.todoroo.astrid.data.RemoteModel;
import com.todoroo.astrid.data.TagData;
import com.todoroo.astrid.data.TagOutstanding;
import com.todoroo.astrid.data.Task;
import com.todoroo.astrid.data.TaskOutstanding;

public class ActFmSyncThread {

    private final Queue<Pair<Long, ModelType>> changesQueue;
    private final Object monitor;
    private Thread thread;

    @Autowired
    private TaskDao taskDao;

    @Autowired
    private TagDataDao tagDataDao;

    @Autowired
    private TaskOutstandingDao taskOutstandingDao;

    @Autowired
    private TagOutstandingDao tagOutstandingDao;

    public static enum ModelType {
        TYPE_TASK,
        TYPE_TAG
    }

    public ActFmSyncThread(Queue<Pair<Long, ModelType>> queue, Object syncMonitor) {
        DependencyInjectionService.getInstance().inject(this);
        this.changesQueue = queue;
        this.monitor = syncMonitor;
    }

    public synchronized void startSyncThread() {
        if (thread == null || !thread.isAlive()) {
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    sync();
                }
            });
            thread.start();
        }
    }

    private void sync() {
        try {
            int batchSize = 1;
            List<ClientToServerMessage<?>> messages = new LinkedList<ClientToServerMessage<?>>();
            while(true) {
                synchronized(monitor) {
                    while (changesQueue.isEmpty() && !timeForBackgroundSync()) {
                        try {
                            monitor.wait();
                        } catch (InterruptedException e) {
                            // Ignored
                        }
                    }
                }

                // Stuff in the document
                while (messages.size() < batchSize && !changesQueue.isEmpty()) {
                    Pair<Long, ModelType> tuple = changesQueue.poll();
                    if (tuple != null) {
                        ChangesHappened<?, ?> changes = getChangesHappened(tuple);
                        if (changes != null)
                            messages.add(changes);
                    }
                }

                if (messages.isEmpty() && timeForBackgroundSync()) {
                    messages.add(getBriefMe(Task.class));
                    messages.add(getBriefMe(TagData.class));
                }

                if (!messages.isEmpty()) {
                    // Get List<ServerToClientMessage> responses
                    // foreach response response.process
                    // if (responses.didntFinish) batchSize = Math.max(batchSize / 2, 1)
                    // else batchSize = min(batchSize, messages.size()) * 2
                    messages = new LinkedList<ClientToServerMessage<?>>();
                }
            }
        } catch (Exception e) {
            // In the worst case, restart thread if something goes wrong
            thread = null;
            startSyncThread();
        }

    }

    private ChangesHappened<?, ?> getChangesHappened(Pair<Long, ModelType> tuple) {
        ModelType modelType = tuple.getRight();
        switch(modelType) {
        case TYPE_TASK:
            return new ChangesHappened<Task, TaskOutstanding>(tuple.getLeft(), Task.class, taskDao, taskOutstandingDao);
        case TYPE_TAG:
            return new ChangesHappened<TagData, TagOutstanding>(tuple.getLeft(), TagData.class, tagDataDao, tagOutstandingDao);
        default:
            return null;
        }
    }

    private <TYPE extends RemoteModel> BriefMe<TYPE> getBriefMe(Class<TYPE> cls) {
        // TODO: compute last pushed at value for model class
        long pushedAt = 0;
        return new BriefMe<TYPE>(cls, null, pushedAt);
    }

    private boolean timeForBackgroundSync() {
        return true;
    }

}