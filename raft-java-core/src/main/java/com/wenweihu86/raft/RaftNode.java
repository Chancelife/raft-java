package com.wenweihu86.raft;

import com.wenweihu86.raft.proto.Raft;
import com.wenweihu86.raft.storage.SegmentedLog;
import com.wenweihu86.rpc.client.RPCCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by wenweihu86 on 2017/5/2.
 */
public class RaftNode {

    public enum NodeState {
        STATE_FOLLOWER,
        STATE_CANDIDATE,
        STATE_LEADER
    }

    private static final Logger LOG = LoggerFactory.getLogger(RaftNode.class);

    private Lock lock = new ReentrantLock();
    private NodeState state = NodeState.STATE_FOLLOWER;
    // 服务器最后一次知道的任期号（初始化为 0，持续递增）
    private long currentTerm;
    // 在当前获得选票的候选人的Id
    private int votedFor;
    private List<Raft.LogEntry> entries;
    // 已知的最大的已经被提交的日志条目的索引值
    private long commitIndex;
    // The index of the last log entry that has been flushed to disk.
    // Valid for leaders only.
    private long lastSyncedIndex;
    // 最后被应用到状态机的日志条目索引值（初始化为 0，持续递增）
    private long lastApplied;
    private long lastSnapshotIndex;
    private long lastSnapshotTerm;
    private List<Peer> peers;
    private ServerAddress localServer;
    private int leaderId; // leader节点id
    private SegmentedLog raftLog;

    private ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture voteScheduledFuture;

    public RaftNode(int localServerId, List<ServerAddress> servers) {
        for (ServerAddress server : servers) {
            if (server.getServerId() == localServerId) {
                this.localServer = server;
            } else {
                Peer peer = new Peer(server);
                peers.add(peer);
            }
        }
        resetElectionTimer();
        raftLog = new SegmentedLog();
        stepDown(1);
    }

    public void init() {
        this.currentTerm = raftLog.getMetaData().getCurrentTerm();
        this.votedFor = raftLog.getMetaData().getVotedFor();
    }

    public void resetElectionTimer() {
        if (voteScheduledFuture != null) {
            voteScheduledFuture.cancel(true);
        }
        voteScheduledFuture = scheduledExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                // TODO
            }
        }, getElectionTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    public void startNewElection() {
        currentTerm++;
        state = NodeState.STATE_CANDIDATE;
        leaderId = 0;
        votedFor = localServer.getServerId();
        // TODO: requestVote to peers
    }

    public void requestVote() {
        Raft.VoteRequest request = Raft.VoteRequest.newBuilder()
                .setServerId(localServer.getServerId())
                .setTerm(currentTerm)
                .setLastLogIndex(raftLog.getLastLogIndex())
                .setLastLogTerm(raftLog.getLastLogTerm()).build();
        for (Peer peer : peers) {
            peer.getRpcClient().asyncCall(
                    "RaftApi.requestVote", request,
                    new VoteResponseCallback(peer));
        }
    }

    private class VoteResponseCallback implements RPCCallback<Raft.VoteResponse> {
        private Peer peer;

        public VoteResponseCallback(Peer peer) {
            this.peer = peer;
        }

        @Override
        public void success(Raft.VoteResponse response) {
            if (response.getTerm() > currentTerm) {
                LOG.info("Received RequestVote response from server {} " +
                                "in term {} (this server's term was {})",
                        peer.getServerAddress().getServerId(),
                        response.getTerm(),
                        currentTerm);
                stepDown(response.getTerm());
            } else {
                if (response.getGranted()) {
                    LOG.info("Got vote from server {} for term {}",
                            peer.getServerAddress().getServerId(), currentTerm);
                    int voteGrantedNum = 1;
                    for (Peer peer1 : peers) {
                        if (peer1.isVoteGranted()) {
                            voteGrantedNum += 1;
                        }
                    }
                    if (voteGrantedNum > (peers.size() + 1) / 2) {
                        becomeLeader();
                    }
                } else {
                    LOG.info("Vote denied by server {} for term {}",
                            peer.getServerAddress().getServerId(), currentTerm);
                }
            }
        }

        @Override
        public void fail(Throwable e) {
            LOG.warn("requestVote with peer[{}:{}] failed",
                    peer.getServerAddress().getHost(),
                    peer.getServerAddress().getPort());
        }

    }

    public void appendEntries(Peer peer) {
        long startLogIndex = raftLog.getStartLogIndex();
        if (peer.getNextIndex() < startLogIndex) {
            this.installSnapshot(peer);
            return;
        }

        long lastLogIndex = this.raftLog.getLastLogIndex();
        long prevLogIndex = peer.getNextIndex() - 1;
        long prevLogTerm = 0;
        if (prevLogIndex >= startLogIndex) {
            prevLogTerm = raftLog.getEntry(prevLogIndex).getTerm();
        } else if (prevLogIndex == 0) {
            prevLogTerm = 0;
        } else if (prevLogIndex == lastSnapshotIndex) {
            prevLogTerm = lastSnapshotTerm;
        } else {
            installSnapshot(peer);
            return;
        }

        Raft.AppendEntriesRequest.Builder requestBuilder = Raft.AppendEntriesRequest.newBuilder();
        requestBuilder.setServerId(localServer.getServerId());
        requestBuilder.setTerm(currentTerm);
        requestBuilder.setPrevLogTerm(prevLogTerm);
        requestBuilder.setPrevLogIndex(prevLogIndex);
        long numEntries = packEntries(peer.getNextIndex(), requestBuilder);
        requestBuilder.setCommitIndex(Math.min(commitIndex, prevLogIndex + numEntries));
        Raft.AppendEntriesRequest request = requestBuilder.build();

        Raft.AppendEntriesResponse response = peer.getRaftApi().appendEntries(request);
        if (response == null) {
            LOG.warn("appendEntries with peer[{}:{}] failed",
                    peer.getServerAddress().getHost(),
                    peer.getServerAddress().getPort());
            return;
        }
        if (response.getTerm() > currentTerm) {
            LOG.info("Received AppendEntries response from server {} " +
                    "in term {} (this server's term was {})",
                    peer.getServerAddress().getServerId(),
                    response.getTerm(), currentTerm);
            stepDown(response.getTerm());
        } else {
            if (response.getSuccess()) {
                peer.setMatchIndex(prevLogIndex + numEntries);
                advanceCommitIndex();
                peer.setNextIndex(peer.getMatchIndex() + 1);
            } else {
                if (peer.getNextIndex() > 1) {
                    peer.setNextIndex(peer.getNextIndex() - 1);
                }
                if (response.getLastLogIndex() != 0
                        && peer.getNextIndex() > response.getLastLogIndex() + 1) {
                    peer.setNextIndex(response.getLastLogIndex() + 1);
                }
            }
        }
    }

    public void advanceCommitIndex() {
        // 获取quorum matchIndex
        int peerNum = peers.size();
        long[] matchIndexes = new long[peerNum + 1];
        for (int i = 0; i < peerNum - 1; i++) {
            matchIndexes[i] = peers.get(i).getMatchIndex();
        }
        matchIndexes[peerNum] = lastSyncedIndex;
        Arrays.sort(matchIndexes);
        long newCommitIndex = matchIndexes[(peerNum + 1 + 1) / 2];

        if (commitIndex >= newCommitIndex) {
            return;
        }
        if (raftLog.getEntry(newCommitIndex).getTerm() != currentTerm) {
            return;
        }
        commitIndex = newCommitIndex;
        // TODO: 同步到状态机
    }

    public long packEntries(long nextIndex, Raft.AppendEntriesRequest.Builder requestBuilder) {
        long lastIndex = Math.min(raftLog.getLastLogIndex(),
                nextIndex + RaftOption.maxLogEntriesPerRequest - 1);
        for (long index = nextIndex; index <= lastIndex; index++) {
            Raft.LogEntry entry = raftLog.getEntry(index);
            requestBuilder.addEntries(entry);
        }
        return lastIndex - nextIndex + 1;
    }

    public void installSnapshot(Peer peer) {
    }

    public void becomeLeader() {
        state = NodeState.STATE_LEADER;
        leaderId = localServer.getServerId();
        // TODO: send AppendEntries to peers
    }

//    public long getStartLogIndex() {
//        return raftLog.getStartLogIndex();
//    }
//
//    public long getLastLogIndex() {
//        return raftLog.getLastLogIndex();
//    }
//
//    public long getLastLogTerm() {
//        long lastLogIndex = raftLog.getLastLogIndex();
//        return raftLog.getEntry(lastLogIndex).getTerm();
//    }
//
//    public Raft.LogEntry getEntry(long index) {
//        return raftLog.getEntry(index);
//    }

    public void stepDown(long newTerm) {
        assert this.currentTerm <= newTerm;
        if (this.currentTerm < newTerm) {
            currentTerm = newTerm;
            leaderId = -1;
            votedFor = -1;
            state = NodeState.STATE_FOLLOWER;
            raftLog.updateMetaData(currentTerm, votedFor, null);
        } else {
            if (state != NodeState.STATE_FOLLOWER) {
                state = NodeState.STATE_FOLLOWER;
            }
        }
    }

    public void updateMetaData() {
        raftLog.updateMetaData(currentTerm, votedFor, null);
    }

    public int getElectionTimeoutMs() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int randomElectionTimeout = RaftOption.electionTimeoutMilliseconds
                + random.nextInt(0, RaftOption.electionTimeoutMilliseconds);
        return randomElectionTimeout;
    }

    public Lock getLock() {
        return lock;
    }

    public void setLock(Lock lock) {
        this.lock = lock;
    }

    public NodeState getState() {
        return state;
    }

    public void setState(NodeState state) {
        this.state = state;
    }

    public long getCurrentTerm() {
        return currentTerm;
    }

    public void setCurrentTerm(int currentTerm) {
        this.currentTerm = currentTerm;
    }

    public int getVotedFor() {
        return votedFor;
    }

    public void setVotedFor(int votedFor) {
        this.votedFor = votedFor;
    }

    public List<Raft.LogEntry> getEntries() {
        return entries;
    }

    public void setEntries(List<Raft.LogEntry> entries) {
        this.entries = entries;
    }

    public long getCommitIndex() {
        return commitIndex;
    }

    public void setCommitIndex(long commitIndex) {
        this.commitIndex = commitIndex;
    }

    public long getLastApplied() {
        return lastApplied;
    }

    public void setLastApplied(long lastApplied) {
        this.lastApplied = lastApplied;
    }

    public ServerAddress getLocalServer() {
        return localServer;
    }

    public void setLocalServer(ServerAddress localServer) {
        this.localServer = localServer;
    }

    public SegmentedLog getRaftLog() {
        return raftLog;
    }

    public ScheduledFuture getVoteScheduledFuture() {
        return voteScheduledFuture;
    }

    public void setVoteScheduledFuture(ScheduledFuture voteScheduledFuture) {
        this.voteScheduledFuture = voteScheduledFuture;
    }

    public ScheduledExecutorService getScheduledExecutor() {
        return scheduledExecutor;
    }

    public int getLeaderId() {
        return leaderId;
    }

    public void setLeaderId(int leaderId) {
        this.leaderId = leaderId;
    }
}