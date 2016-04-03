package org.qcri.rheem.java.operators;

import org.qcri.rheem.basic.operators.GlobalReduceOperator;
import org.qcri.rheem.core.api.Configuration;
import org.qcri.rheem.core.function.ReduceDescriptor;
import org.qcri.rheem.core.optimizer.costs.DefaultLoadEstimator;
import org.qcri.rheem.core.optimizer.costs.LoadEstimator;
import org.qcri.rheem.core.optimizer.costs.LoadProfileEstimator;
import org.qcri.rheem.core.optimizer.costs.NestableLoadProfileEstimator;
import org.qcri.rheem.core.plan.rheemplan.ExecutionOperator;
import org.qcri.rheem.core.types.DataSetType;
import org.qcri.rheem.java.channels.ChannelExecutor;
import org.qcri.rheem.java.compiler.FunctionCompiler;
import org.qcri.rheem.java.execution.JavaExecutor;

import java.util.Collections;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Function;

/**
 * Java implementation of the {@link GlobalReduceOperator}.
 */
public class JavaGlobalReduceOperator<Type>
        extends GlobalReduceOperator<Type>
        implements JavaExecutionOperator {


    /**
     * Creates a new instance.
     *
     * @param type             type of the reduce elements (i.e., type of {@link #getInput()} and {@link #getOutput()})
     * @param reduceDescriptor describes the reduction to be performed on the elements
     */
    public JavaGlobalReduceOperator(DataSetType<Type> type,
                                    ReduceDescriptor<Type> reduceDescriptor) {
        super(reduceDescriptor, type);
    }

    @Override
    public void open(ChannelExecutor[] inputs, FunctionCompiler compiler) {
        final BiFunction<Type, Type, Type> udf = compiler.compile(this.reduceDescriptor);
        JavaExecutor.openFunction(this, udf, inputs);
    }

    @Override
    public void evaluate(ChannelExecutor[] inputs, ChannelExecutor[] outputs, FunctionCompiler compiler) {
        assert inputs.length == this.getNumInputs();
        assert outputs.length == this.getNumOutputs();

        final BinaryOperator<Type> reduceFunction = compiler.compile(this.reduceDescriptor);
        JavaExecutor.openFunction(this, reduceFunction, inputs);

        final Optional<Type> reduction = inputs[0].<Type>provideStream().reduce(reduceFunction);
        outputs[0].acceptCollection(reduction.isPresent() ?
                Collections.singleton(reduction.get()) :
                Collections.emptyList());
    }

    @Override
    public Optional<LoadProfileEstimator> getLoadProfileEstimator(Configuration configuration) {
        return Optional.of(new NestableLoadProfileEstimator(
                new DefaultLoadEstimator(1, 1, 0.9d, (inCards, outCards) -> 25 * inCards[0] + 350000),
                LoadEstimator.createFallback(1, 1)
        ));
    }

    @Override
    protected ExecutionOperator createCopy() {
        return new JavaGlobalReduceOperator<>(this.getInputType(), this.getReduceDescriptor());
    }
}