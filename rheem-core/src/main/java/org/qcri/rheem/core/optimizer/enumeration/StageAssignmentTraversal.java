package org.qcri.rheem.core.optimizer.enumeration;

import org.apache.commons.lang3.Validate;
import org.qcri.rheem.core.plan.executionplan.*;
import org.qcri.rheem.core.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Created by basti on 02/11/16.
 */
public class StageAssignmentTraversal {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    /**
     * Should be turned into a {@link ExecutionPlan}.
     */
    private final PreliminaryExecutionPlan preliminaryExecutionPlan;

    private Queue<ExecutionTask> seeds;

    private Map<ExecutionTask, InterimStage> assignedInterimStages;

    private Map<ExecutionTask, Set<InterimStage>> requiredStages;

    private Collection<InterimStage> allStages, changedStages;

    public StageAssignmentTraversal(PreliminaryExecutionPlan preliminaryExecutionPlan) {
        final Set<ExecutionTask> executionTasks = preliminaryExecutionPlan.collectAllTasks();
        for (ExecutionTask executionTask : executionTasks) {
            for (int i = 0; i < executionTask.getInputChannels().length; i++) {
                Channel channel = executionTask.getInputChannels()[i];
                if (channel == null) {
                    this.logger.warn("{} does not have an input channel @{}.", executionTask, i);
                }
            }
            for (int i = 0; i < executionTask.getOutputChannels().length; i++) {
                Channel channel = executionTask.getOutputChannels()[i];
                if (channel == null) {
                    this.logger.warn("{} does not have an output channel @{}.", executionTask, i);
                }
            }
        }
        assert preliminaryExecutionPlan.isComplete();
        this.preliminaryExecutionPlan = preliminaryExecutionPlan;
    }

    public synchronized ExecutionPlan run() {
        // Create initial stages.
        this.initializeRun();
        this.discoverInitialStages();

        // Refine stages as much as necessary
        this.refineStages();
        for (InterimStage stage : this.allStages) {
            this.logger.info("Final stage {}: {}", stage, stage.getTasks());
        }

        // Assemble the ExecutionPlan.
        return this.assembleExecutionPlan();
    }

    private void initializeRun() {
        this.seeds = new LinkedList<>(this.preliminaryExecutionPlan.getSinkTasks());
        this.assignedInterimStages = new HashMap<>();
        this.requiredStages = new HashMap<>();
        this.changedStages = new LinkedList<>();
        this.allStages = new LinkedList<>();
    }

    private void discoverInitialStages() {
        while (!this.seeds.isEmpty()) {
            final ExecutionTask task = this.seeds.poll();
            if (this.assignedInterimStages.containsKey(task)) {
                continue;
            }
            Platform platform = task.getOperator().getPlatform();
            PlatformExecution platformExecution = new PlatformExecution(platform);
            InterimStage initialStage = new InterimStage(platformExecution);
            this.allStages.add(initialStage);
            this.traverseTask(task, initialStage);
        }
        this.changedStages.addAll(this.allStages);
    }

    private void traverseTask(ExecutionTask task, InterimStage interimStage) {
        this.assign(task, interimStage);
        this.expandDownstream(task, interimStage);
        this.expandUpstream(task, interimStage);
    }

    private void assign(ExecutionTask task, InterimStage newStage) {
        Validate.isTrue(task.getOperator().getPlatform().equals(newStage.getPlatform()));
        newStage.addTask(task);
        final InterimStage oldStage = this.assignedInterimStages.put(task, newStage);
        Validate.isTrue(oldStage == null, "Reassigned %s from %s to %s.", task, oldStage, newStage);
        final HashSet<InterimStage> thisRequiredStages = new HashSet<>(4);
        thisRequiredStages.add(newStage);
        this.requiredStages.put(task, thisRequiredStages);
        this.logger.debug("Initially assigning {} to {}.", task, newStage);
    }

    private void expandDownstream(ExecutionTask task, InterimStage expandableStage) {
        for (Channel channel : task.getOutputChannels()) {
            if (channel.isExecutionBreaker()) {
                expandableStage.setOutbound(task);
            }
            for (ExecutionTask consumer : channel.getConsumers()) {
                final InterimStage assignedStage = this.assignedInterimStages.get(consumer);
                if (assignedStage == null) {
                    this.handleTaskWithoutPlatformExecution(consumer, /*channel.isExecutionBreaker() ? null : */ expandableStage);
                }
            }
        }
    }

    private void expandUpstream(ExecutionTask task, InterimStage expandableStage) {
        for (Channel channel : task.getInputChannels()) {
            final ExecutionTask producer = channel.getProducer();
            Validate.notNull(producer);
            final InterimStage assignedStage = this.assignedInterimStages.get(producer);
            if (assignedStage == null) {
                this.handleTaskWithoutPlatformExecution(producer, /*channel.isExecutionBreaker() ? null : */expandableStage);
            }
        }
    }

    private void handleTaskWithoutPlatformExecution(ExecutionTask task, InterimStage expandableStage) {
        final Platform operatorPlatform = task.getOperator().getPlatform();
        if (expandableStage != null && operatorPlatform.equals(expandableStage.getPlatform())) {
            this.traverseTask(task, expandableStage);
        } else {
            this.seeds.add(task);
        }
    }

    /**
     * Refine the current {@link #changedStages} and put the result to {@link #allStages}.
     */
    private void refineStages() {
        while (!this.changedStages.isEmpty()) {
            // Update the precedence graph.
            for (InterimStage currentStage : this.changedStages) {
                for (ExecutionTask outboundTask : currentStage.getOutboundTasks()) {
                    this.updateRequiredStages(outboundTask, new HashSet<>());
                }
            }

            // Partition stages.
            this.changedStages.clear();
            for (InterimStage stage : this.allStages) {
                this.partitionStage(stage);
            }
            this.allStages.addAll(this.changedStages);
        }
    }

    /**
     * Update the {@link #requiredStages} while recursively traversing downstream over {@link ExecutionTask}s.
     * Also, mark its {@link InterimStage} ({@link InterimStage#mark()})
     * to keep track of dependencies between the {@link InterimStage}s that we have been unaware of so far.
     *
     * @param task           which is to be traversed next
     * @param requiredStages the required {@link InterimStage}s
     */
    private void updateRequiredStages(ExecutionTask task, Set<InterimStage> requiredStages) {
        final InterimStage currentStage = this.assignedInterimStages.get(task);
        if (!requiredStages.contains(currentStage)) {
            requiredStages = new HashSet<>(requiredStages);
            requiredStages.add(currentStage);
        }
        final Set<InterimStage> currentlyRequiredStages = this.requiredStages.get(task);
        if (currentlyRequiredStages.addAll(requiredStages)) {
            this.logger.debug("Updated required stages of {} to {}.", task, currentlyRequiredStages);
            currentStage.mark();
        }
        for (Channel channel : task.getOutputChannels()) {
            for (ExecutionTask consumingTask : channel.getConsumers()) {
                this.updateRequiredStages(consumingTask, requiredStages);
            }
        }
    }


    /**
     * Partition the {@code stage} into two halves. All {@link ExecutionTask}s that do not have the minimum count of
     * required {@link InterimStage}s will be put into a new {@link InterimStage}.
     */
    private boolean partitionStage(InterimStage stage) {
        // Short-cut: if the stage has not been marked, its required stages did not change.
        if (!stage.getAndResetMark()) {
            return false;
        }
        int minRequiredStages = -1;
        final Collection<ExecutionTask> initialTasks = new LinkedList<>();
        final Set<ExecutionTask> separableTasks = new HashSet<>();
        for (ExecutionTask task : stage.getTasks()) {
            final Set<InterimStage> requiredStages = this.requiredStages.get(task);
            if (minRequiredStages == -1 || requiredStages.size() < minRequiredStages) {
                separableTasks.addAll(initialTasks);
                initialTasks.clear();
                minRequiredStages = requiredStages.size();
            }
            (minRequiredStages == requiredStages.size() ? initialTasks : separableTasks).add(task);
        }

        if (separableTasks.isEmpty()) {
            this.logger.debug("No separable tasks found in marked stage {}.", stage);
            return false;
        } else {
            this.logger.debug("Separating " + separableTasks + " from " + stage + "...");
            InterimStage newStage = stage.separate(separableTasks);
            this.changedStages.add(newStage);
            for (ExecutionTask separatedTask : newStage.getTasks()) {
                this.assignedInterimStages.put(separatedTask, newStage);
            }
            return true;
        }
    }

    private ExecutionPlan assembleExecutionPlan() {
        final Map<InterimStage, ExecutionStage> finalStages = new HashMap<>(this.allStages.size());
        for (ExecutionTask sinkTask : this.preliminaryExecutionPlan.getSinkTasks()) {
            this.assembleExecutionPlan(finalStages, null, sinkTask);
        }
        final ExecutionPlan executionPlan = new ExecutionPlan();
        finalStages.values().stream().filter(ExecutionStage::isStartingStage).forEach(executionPlan::addStartingStage);
        return executionPlan;
    }

    private void assembleExecutionPlan(Map<InterimStage, ExecutionStage> finalStages,
                                       ExecutionStage successorExecutionStage,
                                       ExecutionTask currentExecutionTask) {

        final InterimStage interimStage = this.assignedInterimStages.get(currentExecutionTask);
        ExecutionStage executionStage = finalStages.get(interimStage);
        if (executionStage == null) {
            executionStage = interimStage.toExecutionStage();
            finalStages.put(interimStage, executionStage);
        }

        if (successorExecutionStage != null
                && !executionStage.equals(successorExecutionStage)
                && !executionStage.getSuccessors().contains(successorExecutionStage)) {
            executionStage.addSuccessor(successorExecutionStage);
        }

        for (Channel channel : currentExecutionTask.getInputChannels()) {
            final ExecutionTask predecessor = channel.getProducer();
            this.assembleExecutionPlan(finalStages, executionStage, predecessor);
        }
    }

    private class InterimStage {

        /**
         * The {@link PlatformExecution} to that this instance belongs to.
         */
        private final PlatformExecution platformExecution;

        /**
         * All tasks being in this instance.
         */
        private final Set<ExecutionTask> allTasks = new HashSet<>();

        /**
         * All tasks that feed a {@link Channel} that is consumed by a different {@link PlatformExecution}.
         */
        private final Set<ExecutionTask> outboundTasks = new HashSet<>();

        /**
         * Use for mark-and-sweep algorithms. (Specifically: mark changed stages)
         */
        private boolean isMarked;

        /**
         * For debugging purposes only.
         */
        private final int sequenceNumber;

        /**
         * Creates a new instance.
         */
        public InterimStage(PlatformExecution platformExecution) {
            this(platformExecution, 0);
        }

        private InterimStage(PlatformExecution platformExecution, int sequenceNumber) {
            this.platformExecution = platformExecution;
            this.sequenceNumber = sequenceNumber;
        }

        public Platform getPlatform() {
            return this.platformExecution.getPlatform();
        }

        public void addTask(ExecutionTask task) {
            this.allTasks.add(task);
        }

        public void setOutbound(ExecutionTask task) {
            Validate.isTrue(this.allTasks.contains(task));
            this.outboundTasks.add(task);
        }

        public Set<ExecutionTask> getOutboundTasks() {
            return this.outboundTasks;
        }

        public Set<ExecutionTask> getTasks() {
            return this.allTasks;
        }

        public InterimStage separate(Set<ExecutionTask> separableTasks) {
            InterimStage newStage = this.createSplit();
            for (Iterator<ExecutionTask> i = this.allTasks.iterator(); i.hasNext(); ) {
                final ExecutionTask task = i.next();
                if (separableTasks.contains(task)) {
                    i.remove();
                    newStage.addTask(task);
                    if (this.outboundTasks.remove(task)) {
                        newStage.setOutbound(task);
                    }
                }
            }

            // NB: We do not take care of maintaining the outbound tasks of this instance, because we do not need them
            // any more.
            return newStage;
        }

        public InterimStage createSplit() {
            return new InterimStage(this.platformExecution, this.sequenceNumber + 1);
        }

        public void mark() {
            this.isMarked = true;
        }

        public boolean isMarked() {
            return this.isMarked;
        }

        public boolean getAndSetMark() {
            final boolean value = this.isMarked;
            this.isMarked = true;
            return value;
        }

        public boolean getAndResetMark() {
            final boolean value = this.isMarked;
            this.isMarked = false;
            return value;
        }

        @Override
        public String toString() {
            return String.format("InterimStage[%s:%d]", this.getPlatform().getName(), this.sequenceNumber);
        }

        public ExecutionStage toExecutionStage() {
            final ExecutionStage executionStage = this.platformExecution.createStage(this.sequenceNumber);
            for (ExecutionTask task : this.allTasks) {
                executionStage.addTask(task);
                if (this.checkIfStartTask(task)) {
                    executionStage.markAsStartTast(task);
                } else if (this.checkIfTerminalTask(task)) {
                    executionStage.markAsTerminalTask(task);
                }
            }
            return executionStage;
        }

        private boolean checkIfStartTask(ExecutionTask task) {
            for (Channel channel : task.getInputChannels()) {
                final ExecutionTask producer = channel.getProducer();
                if (this.equals(StageAssignmentTraversal.this.assignedInterimStages.get(producer))) {
                    return false;
                }
            }
            return true;
        }

        private boolean checkIfTerminalTask(ExecutionTask task) {
            for (Channel channel : task.getOutputChannels()) {
                for (ExecutionTask consumer : channel.getConsumers()) {
                    if (this.equals(StageAssignmentTraversal.this.assignedInterimStages.get(consumer))) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

}
