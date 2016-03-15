package org.qcri.rheem.java.channels;

import org.apache.commons.lang3.Validate;
import org.qcri.rheem.basic.channels.FileChannel;
import org.qcri.rheem.core.api.exception.RheemException;
import org.qcri.rheem.core.plan.executionplan.Channel;
import org.qcri.rheem.core.plan.executionplan.ChannelInitializer;
import org.qcri.rheem.core.plan.executionplan.ExecutionTask;
import org.qcri.rheem.core.platform.ChannelDescriptor;
import org.qcri.rheem.core.types.DataSetType;
import org.qcri.rheem.java.JavaPlatform;
import org.qcri.rheem.java.operators.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.stream.Stream;

/**
 * Sets up {@link FileChannel} usage in the {@link JavaPlatform}.
 */
public class HdfsFileInitializer implements ChannelInitializer {

    @SuppressWarnings("unused")
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Override
    public Channel setUpOutput(ChannelDescriptor fileDescriptor, ExecutionTask sourceTask, int index) {
        // Set up an internal Channel at first.
        final ChannelInitializer streamChannelInitializer = sourceTask.getOperator().getPlatform()
                .getChannelManager().getChannelInitializer(StreamChannel.DESCRIPTOR);
        assert streamChannelInitializer != null;
        final Channel internalChannel = streamChannelInitializer.setUpOutput(StreamChannel.DESCRIPTOR, sourceTask, index);

        // Create a sink to write the HDFS file.
        ExecutionTask sinkTask;
        final String targetPath = FileChannel.pickTempPath();
        final String serialization = ((FileChannel.Descriptor) fileDescriptor).getSerialization();
        switch (serialization) {
            case "object-file":
                sinkTask = this.setUpJavaObjectFileSink(sourceTask, index, internalChannel, targetPath);
                break;
            case "tsv":
                sinkTask = this.setUpTsvFileSink(sourceTask, index, internalChannel, targetPath);
                break;
            default:
                throw new IllegalStateException(String.format("Unsupported serialization: \"%s\"", serialization));
        }

        // Check if the final FileChannel already exists.
        assert sinkTask.getOutputChannels().length == 1;
        if (sinkTask.getOutputChannel(0) != null) {
            assert sinkTask.getOutputChannel(0) instanceof FileChannel :
                    String.format("Expected %s, found %s.", FileChannel.class.getSimpleName(), sinkTask.getOutputChannel(0));
            return sinkTask.getOutputChannel(0);
        }

        // Create the actual FileChannel.
        final FileChannel fileChannel = new FileChannel((FileChannel.Descriptor) fileDescriptor, sinkTask, index,
                Channel.extractCardinalityEstimate(sourceTask, index));
        fileChannel.addPath(targetPath);
        fileChannel.addSibling(internalChannel);
        return fileChannel;
    }

    private ExecutionTask setUpJavaObjectFileSink(ExecutionTask sourceTask, int outputIndex, Channel internalChannel, String targetPath) {
        // Check if the Channel already is consumed by a JavaObjectFileSink.
        for (ExecutionTask consumerTask : internalChannel.getConsumers()) {
            if (consumerTask.getOperator() instanceof JavaObjectFileSink<?>) {
                return consumerTask;
            }
        }

        // Create the JavaObjectFileSink.
        final DataSetType<?> dataSetType = sourceTask.getOperator().getOutput(outputIndex).getType();
        JavaObjectFileSink<?> javaObjectFileSink = new JavaObjectFileSink<>(targetPath, dataSetType);
        javaObjectFileSink.getInput(0).setCardinalityEstimate(Channel.extractCardinalityEstimate(sourceTask, outputIndex));
        ExecutionTask sinkTask = new ExecutionTask(javaObjectFileSink, javaObjectFileSink.getNumInputs(), 1);

        // Connect it to the internalChannel.
        final ChannelInitializer channelInitializer = javaObjectFileSink
                .getPlatform()
                .getChannelManager()
                .getChannelInitializer(internalChannel.getDescriptor());
        channelInitializer.setUpInput(internalChannel, sinkTask, 0);

        return sinkTask;
    }

    private ExecutionTask setUpTsvFileSink(ExecutionTask sourceTask, int outputIndex, Channel internalChannel, String targetPath) {
        // Check if the Channel already is consumed by a JavaObjectFileSink.
        for (ExecutionTask consumerTask : internalChannel.getConsumers()) {
            if (consumerTask.getOperator() instanceof JavaTsvFileSink<?>) {
                return consumerTask;
            }
        }

        // Create the JavaObjectFileSink.
        final DataSetType<?> dataSetType = sourceTask.getOperator().getOutput(outputIndex).getType();
        JavaTsvFileSink<?> javaTsvFileSink = new JavaTsvFileSink<>(targetPath, dataSetType);
        javaTsvFileSink.getInput(0).setCardinalityEstimate(Channel.extractCardinalityEstimate(sourceTask, outputIndex));
        ExecutionTask sinkTask = new ExecutionTask(javaTsvFileSink, javaTsvFileSink.getNumInputs(), 1);

        // Connect it to the internalChannel.
        final ChannelInitializer channelInitializer = javaTsvFileSink
                .getPlatform()
                .getChannelManager()
                .getChannelInitializer(internalChannel.getDescriptor());
        channelInitializer.setUpInput(internalChannel, sinkTask, 0);

        return sinkTask;
    }

    @Override
    public void setUpInput(Channel channel, ExecutionTask targetTask, int inputIndex) {
        FileChannel fileChannel = (FileChannel) channel;
        assert fileChannel.getPaths().size() == 1 :
                String.format("We support only single HDFS files so far (found %d).", fileChannel.getPaths().size());

        // NB: We always put the HDFS file contents into a Collection. That's not necessary if we don't broadcast
        // and use it only once.

        // Intercept with a reader for the file.
        ExecutionTask sourceTask;
        final String serialization = fileChannel.getDescriptor().getSerialization();
        switch (serialization) {
            case "object-file":
                sourceTask = this.setUpJavaObjectFileSource(fileChannel);
                break;
            case "tsv":
                sourceTask = this.setUpTsvFileSource(fileChannel);
                break;
            default:
                throw new IllegalStateException(
                        String.format("%s cannot handle \"%s\" serialization.", targetTask.getPlatform(), serialization));
        }

        // Set up the actual input..
        final ChannelInitializer internalChannelInitializer = targetTask.getOperator().getPlatform().getChannelManager()
                .getChannelInitializer(CollectionChannel.DESCRIPTOR);
        Validate.notNull(internalChannelInitializer);
        final Channel internalChannel = internalChannelInitializer.setUpOutput(CollectionChannel.DESCRIPTOR, sourceTask, 0);
        internalChannel.addSibling(fileChannel);
        internalChannelInitializer.setUpInput(internalChannel, targetTask, inputIndex);
    }

    private ExecutionTask setUpJavaObjectFileSource(FileChannel fileChannel) {
        // Check if there is already is a JavaObjectFileSource in place.
        for (ExecutionTask consumerTask : fileChannel.getConsumers()) {
            if (consumerTask.getOperator() instanceof JavaObjectFileSource<?>) {
                return consumerTask;
            }
        }

        // Create the JavaObjectFileSink.
        // FIXME: This is neither elegant nor sound, as we make assumptions on the FileChannel producer.
        final DataSetType<?> dataSetType = fileChannel.getProducer().getOperator().getInput(0).getType();
        JavaObjectFileSource<?> javaObjectFileSource = new JavaObjectFileSource<>(fileChannel.getSinglePath(), dataSetType);
        javaObjectFileSource.getOutput(0).setCardinalityEstimate(fileChannel.getCardinalityEstimate());
        ExecutionTask sourceTask = new ExecutionTask(javaObjectFileSource, 1, javaObjectFileSource.getNumOutputs());
        fileChannel.addConsumer(sourceTask, 0);

        return sourceTask;
    }

    private ExecutionTask setUpTsvFileSource(FileChannel fileChannel) {
        // Check if there is already is a JavaTsvFileSource in place.
        for (ExecutionTask consumerTask : fileChannel.getConsumers()) {
            if (consumerTask.getOperator() instanceof JavaTsvFileSource<?>) {
                return consumerTask;
            }
        }

        // Create the JavaObjectFileSink.
        // FIXME: This is neither elegant nor sound, as we make assumptions on the FileChannel producer.
        final DataSetType<?> dataSetType = fileChannel.getProducer().getOperator().getInput(0).getType();
        JavaTsvFileSource<?> tsvSource = new JavaTsvFileSource<>(fileChannel.getSinglePath(), dataSetType);
        tsvSource.getOutput(0).setCardinalityEstimate(fileChannel.getCardinalityEstimate());
        ExecutionTask sourceTask = new ExecutionTask(tsvSource, 1, tsvSource.getNumOutputs());
        fileChannel.addConsumer(sourceTask, 0);

        return sourceTask;
    }

    @Override
    public boolean isReusable() {
        return true;
    }

    @Override
    public boolean isInternal() {
        return false;
    }

    public static class Executor implements ChannelExecutor {

        private final FileChannel fileChannel;

        private boolean isMarkedForInstrumentation;

        private long cardinality = -1;

        private boolean wasTriggered = false;

        public Executor(FileChannel fileChannel) {
            this.fileChannel = fileChannel;
            if (this.fileChannel.isMarkedForInstrumentation()) {
                this.markForInstrumentation();
            }
        }

        @Override
        public void acceptStream(Stream<?> stream) {
            assert stream == null;
            this.wasTriggered = true;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Stream<?> provideStream() {
            assert this.wasTriggered;
            return null;
        }

        @Override
        public void acceptCollection(Collection<?> collection) {
            this.wasTriggered = true;
            assert collection == null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Collection<?> provideCollection() {
            assert this.wasTriggered;
            return null;
        }

        @Override
        public boolean canProvideCollection() {
            return true;
        }

        @Override
        public long getCardinality() throws RheemException {
            assert this.isMarkedForInstrumentation;
            return this.cardinality;
        }

        @Override
        public void markForInstrumentation() {
            this.isMarkedForInstrumentation = true;
            ((JavaExecutionOperator) this.fileChannel.getProducer().getOperator()).instrumentSink(this);
        }

        public void setCardinality(long cardinality) {
            this.cardinality = cardinality;
        }

        @Override
        public boolean ensureExecution() {
            return this.wasTriggered;
        }
    }
}