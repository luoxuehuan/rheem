package org.qcri.rheem.spark.operators;

import org.qcri.rheem.basic.operators.RepeatOperator;
import org.qcri.rheem.core.api.Configuration;
import org.qcri.rheem.core.optimizer.OptimizationContext;
import org.qcri.rheem.core.optimizer.costs.LoadProfileEstimator;
import org.qcri.rheem.core.optimizer.costs.LoadProfileEstimators;
import org.qcri.rheem.core.optimizer.costs.NestableLoadProfileEstimator;
import org.qcri.rheem.core.plan.rheemplan.ExecutionOperator;
import org.qcri.rheem.core.platform.ChannelDescriptor;
import org.qcri.rheem.core.platform.ChannelInstance;
import org.qcri.rheem.core.types.DataSetType;
import org.qcri.rheem.spark.channels.RddChannel;
import org.qcri.rheem.spark.execution.SparkExecutor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Spark implementation of the {@link RepeatOperator}.
 */
public class SparkRepeatOperator<Type>
        extends RepeatOperator<Type>
        implements SparkExecutionOperator {

    /**
     * Keeps track of the current iteration number.
     */
    private int iterationCounter;

    public SparkRepeatOperator(int numIterations, DataSetType<Type> type) {
        super(numIterations, type);
    }

    public SparkRepeatOperator(RepeatOperator<Type> that) {
        super(that);
    }


    @Override
    @SuppressWarnings("unchecked")
    public void evaluate(ChannelInstance[] inputs,
                         ChannelInstance[] outputs,
                         SparkExecutor sparkExecutor,
                         OptimizationContext.OperatorContext operatorContext) {
        assert inputs.length == this.getNumInputs();
        assert outputs.length == this.getNumOutputs();


        RddChannel.Instance iterationInput;
        switch (this.getState()) {
            case NOT_STARTED:
                assert inputs[INITIAL_INPUT_INDEX] != null;
                iterationInput = (RddChannel.Instance) inputs[INITIAL_INPUT_INDEX];
                this.iterationCounter = 0;
                break;
            case RUNNING:
                assert inputs[ITERATION_INPUT_INDEX] != null;
                iterationInput = (RddChannel.Instance) inputs[ITERATION_INPUT_INDEX];
                this.iterationCounter++;
                break;
            default:
                throw new IllegalStateException(String.format("%s is finished, yet executed.", this));

        }

        if (this.iterationCounter >= this.getNumIterations()) {
            // final loop output
            ((RddChannel.Instance) outputs[FINAL_OUTPUT_INDEX]).accept(iterationInput.provideRdd(), sparkExecutor);
            outputs[ITERATION_OUTPUT_INDEX] = null;
            this.setState(State.FINISHED);
        } else {
            outputs[FINAL_OUTPUT_INDEX] = null;
            ((RddChannel.Instance) outputs[ITERATION_OUTPUT_INDEX]).accept(iterationInput.provideRdd(), sparkExecutor);
            this.setState(State.RUNNING);
        }

    }

    @Override
    protected ExecutionOperator createCopy() {
        return new SparkRepeatOperator<>(this);
    }

    @Override
    public Optional<LoadProfileEstimator<ExecutionOperator>> createLoadProfileEstimator(Configuration configuration) {
        final String specification = configuration.getStringProperty("rheem.spark.repeat.load");
        final NestableLoadProfileEstimator<ExecutionOperator> mainEstimator =
                LoadProfileEstimators.createFromJuelSpecification(specification);
        return Optional.of(mainEstimator);
    }

    @Override
    public List<ChannelDescriptor> getSupportedInputChannels(int index) {
        assert index <= this.getNumInputs() || (index == 0 && this.getNumInputs() == 0);
        switch (index) {
            case INITIAL_INPUT_INDEX:
            case ITERATION_INPUT_INDEX:
                return Arrays.asList(RddChannel.UNCACHED_DESCRIPTOR, RddChannel.CACHED_DESCRIPTOR);
            default:
                throw new IllegalStateException(String.format("%s has no %d-th input.", this, index));
        }
    }

    @Override
    public List<ChannelDescriptor> getSupportedOutputChannels(int index) {
        assert index <= this.getNumOutputs() || (index == 0 && this.getNumOutputs() == 0);
        return Collections.singletonList(RddChannel.UNCACHED_DESCRIPTOR);
        // TODO: In this specific case, the actual output Channel is context-sensitive because we could forward Streams/Collections.
    }

    @Override
    public boolean isExecutedEagerly() {
        return true;
    }

    @Override
    public boolean isEvaluatingEagerly(int inputIndex) {
        return false;
    }
}