package org.qcri.rheem.java.operators;

import org.junit.Assert;
import org.junit.Test;
import org.qcri.rheem.basic.data.Tuple2;
import org.qcri.rheem.core.function.ReduceDescriptor;
import org.qcri.rheem.core.types.DataSetType;
import org.qcri.rheem.core.types.DataUnitType;
import org.qcri.rheem.java.channels.ChannelExecutor;
import org.qcri.rheem.java.channels.TestChannelExecutor;
import org.qcri.rheem.java.compiler.FunctionCompiler;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Test suite for {@link JavaGlobalReduceOperator}.
 */
public class JavaGlobalReduceOperatorTest {

    @Test
    public void testExecution() {
        // Prepare test data.
        Stream<Integer> inputStream = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10).stream();

        // Build the reduce operator.
        JavaGlobalReduceOperator<Integer> globalReduce =
                new JavaGlobalReduceOperator<>(
                        DataSetType.createDefaultUnchecked(Tuple2.class),
                        new ReduceDescriptor<>(
                                (a, b) -> a + b, DataUnitType.createGrouped(Integer.class),
                                DataUnitType.createBasic(Integer.class)
                        )
                );

        // Execute.
        ChannelExecutor[] inputs = new ChannelExecutor[]{new TestChannelExecutor(inputStream)};
        ChannelExecutor[] outputs = new ChannelExecutor[]{new TestChannelExecutor()};
        globalReduce.evaluate(inputs, outputs, new FunctionCompiler());

        // Verify the outcome.
        final List<Integer> result = outputs[0].<Integer>provideStream().collect(Collectors.toList());
        Assert.assertEquals(1, result.size());
        Assert.assertEquals(Integer.valueOf((10 + 1) * (10 / 2)), result.get(0)); // Props to Gauss!

    }

    @Test
    public void testExecutionWithoutData() {
        // Prepare test data.
        Stream<Integer> inputStream = Arrays.<Integer>asList().stream();

        // Build the reduce operator.
        JavaGlobalReduceOperator<Integer> globalReduce =
                new JavaGlobalReduceOperator<>(
                        DataSetType.createDefaultUnchecked(Tuple2.class),
                        new ReduceDescriptor<>(
                                (a, b) -> a + b, DataUnitType.createGrouped(Integer.class),
                                DataUnitType.createBasic(Integer.class)
                        )
                );

        // Execute the reduce operator.
        ChannelExecutor[] inputs = new ChannelExecutor[]{new TestChannelExecutor(inputStream)};
        ChannelExecutor[] outputs = new ChannelExecutor[]{new TestChannelExecutor()};
        globalReduce.evaluate(inputs, outputs, new FunctionCompiler());

        // Verify the outcome.
        final List<Integer> result = outputs[0].<Integer>provideStream().collect(Collectors.toList());
        Assert.assertEquals(0, result.size());

    }
}