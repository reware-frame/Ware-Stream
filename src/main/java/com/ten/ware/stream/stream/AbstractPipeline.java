/*
 * Copyright (c) 2012, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.ten.ware.stream.stream;

import java.util.Objects;
import java.util.Spliterator;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Abstract base class for "pipeline" classes, which are the core
 * implementations of the Stream interface and its primitive specializations.
 * Manages construction and evaluation of stream pipelines.
 *
 * <p>An {@code AbstractPipeline} represents an initial portion of a stream
 * pipeline, encapsulating a stream source and zero or more intermediate
 * operations.  The individual {@code AbstractPipeline} objects are often
 * referred to as <em>stages</em>, where each stage describes either the stream
 * source or an intermediate operation.
 *
 * <p>A concrete intermediate stage is generally built from an
 * {@code AbstractPipeline}, a shape-specific pipeline class which extends it
 * (e.g., {@code IntPipeline}) which is also abstract, and an operation-specific
 * concrete class which extends that.  {@code AbstractPipeline} contains most of
 * the mechanics of evaluating the pipeline, and implements methods that will be
 * used by the operation; the shape-specific classes add helper methods for
 * dealing with collection of results into the appropriate shape-specific
 * containers.
 *
 * <p>After chaining a new intermediate operation, or executing a terminal
 * operation, the stream is considered to be consumed, and no more intermediate
 * or terminal operations are permitted on this stream instance.
 * <p>
 * “管道”类的抽象基类，它是流接口及其基本专门化的核心实现。管理流管道的建设和评估。
 * AbstractPipeline表示流管道的初始部分，封装流源和零或多个中间操作。
 * 单独的AbstractPipeline对象通常称为阶段，其中每个阶段描述流源或中间操作。
 * 具体的中间阶段通常由AbstractPipeline、扩展它的特定于形状的管道类(例如IntPipeline)和扩展它的特定于操作的具体类构成。
 * AbstractPipeline包含了评估管道的大部分机制，并实现了操作将使用的方法;
 * 特定于形状的类添加了帮助器方法，用于将结果集合处理到适当的特定于形状的容器中。
 * 在链接一个新的中间操作或执行一个终端操作之后，将认为该流已被消耗，并且不允许在这个流实例上执行更多的中间操作或终端操作。
 *
 * @param <E_IN>  type of input elements
 * @param <E_OUT> type of output elements
 * @param <S>     type of the subclass implementing {@code BaseStream}
 * @implNote <p>For sequential streams, and parallel streams without
 * <a href="package-summary.html#StreamOps">stateful intermediate
 * operations</a>, parallel streams, pipeline evaluation is done in a single
 * pass that "jams" all the operations together.  For parallel streams with
 * stateful operations, execution is divided into segments, where each
 * stateful operations marks the end of a segment, and each segment is
 * evaluated separately and the result used as the input to the next
 * segment.  In all cases, the source data is not consumed until a terminal
 * operation begins.
 * <p>
 * 对于顺序流、没有状态中间操作的并行流、并行流，管道评估是在一次遍历中完成的，它将所有操作“阻塞”在一起。
 * 对于有状态操作的并行流，执行被划分为多个段，其中每个有状态操作都标志着一个段的结束
 * ，每个段分别计算，结果用作下一个段的输入。在所有情况下，直到终端操作开始时才使用源数据。
 * @since 1.8
 */
abstract class AbstractPipeline<E_IN, E_OUT, S extends BaseStream<E_OUT, S>>
        extends PipelineHelper<E_OUT> implements BaseStream<E_OUT, S> {
    private static final String MSG_STREAM_LINKED = "stream has already been operated upon or closed";
    private static final String MSG_CONSUMED = "source already consumed or closed";

    /**
     * Backlink to the head of the pipeline chain (self if this is the source
     * stage).
     * 反向链接到管道链的头部（如果这是源阶段，则为它本身）。
     */
    @SuppressWarnings("rawtypes")
    private final AbstractPipeline sourceStage;

    /**
     * The "upstream" pipeline, or null if this is the source stage.
     * 上游管道，如果是原阶段(也就是第一次创建流)则为null
     */
    @SuppressWarnings("rawtypes")
    private final AbstractPipeline previousStage;

    /**
     * The operation flags for the intermediate operation represented by this
     * pipeline object.
     * 此管道对象中表示的中间操作的操作标志。
     */
    protected final int sourceOrOpFlags;

    /**
     * The next stage in the pipeline, or null if this is the last stage.
     * Effectively final at the point of linking to the next pipeline.
     * 管道中的下一阶段，如果这是最后一阶段，则为null。在连接到下一个管道时有效地结束。
     */
    @SuppressWarnings("rawtypes")
    private AbstractPipeline nextStage;

    /**
     * The number of intermediate operations between this pipeline object
     * and the stream source if sequential, or the previous stateful if parallel.
     * Valid at the point of pipeline preparation for evaluation.
     * 此管道对象与流源（如果是顺序）之间的中间操作数，或之前的有状态（如果并行）。在评估管道准备时有效。
     */
    private int depth;

    /**
     * The combined source and operation flags for the source and all operations
     * up to and including the operation represented by this pipeline object.
     * Valid at the point of pipeline preparation for evaluation.
     * 源以及所有操作的组合源的操作标志，包括此管道对象表示的操作。在评估管道准备时有效。
     */
    private int combinedFlags;

    /**
     * The source spliterator. Only valid for the head pipeline.
     * Before the pipeline is consumed if non-null then {@code sourceSupplier}
     * must be null. After the pipeline is consumed if non-null then is set to
     * null.
     * 源spliterator。仅对头管道有效。在使用管道之前，如果非空，则sourceSupplier必须为空。如果使用了非空管道，则将其设置为null。
     */
    private Spliterator<?> sourceSpliterator;

    /**
     * The source supplier. Only valid for the head pipeline. Before the
     * pipeline is consumed if non-null then {@code sourceSpliterator} must be
     * null. After the pipeline is consumed if non-null then is set to null.
     * 源供应商。仅对头管道有效。如果非空，则在使用管道之前sourceSpliterator必须为空。如果使用了非空管道，则将其设置为null。
     */
    private Supplier<? extends Spliterator<?>> sourceSupplier;

    /**
     * True if this pipeline has been linked or consumed
     * 如果已链接或使用此管道，则为True
     */
    private boolean linkedOrConsumed;

    /**
     * True if there are any stateful ops in the pipeline; only valid for the
     * source stage.
     * 如果管道中有任何有状态的操作，则为真;仅对源阶段有效。
     */
    private boolean sourceAnyStateful;

    private Runnable sourceCloseAction;

    /**
     * True if pipeline is parallel, otherwise the pipeline is sequential; only
     * valid for the source stage.
     * 如果管道是并行的，则为真，否则管道是顺序的;仅对源阶段有效.
     */
    private boolean parallel;

    /**
     * Constructor for the head of a stream pipeline.
     * <p>
     * 在我们调用的时候sourceSupplier没有没赋值为null，则源分裂器被赋值为source源流.
     * 由于我是第一次创建，则它的上游管道为null同时反向链接管道的头部也为它本身，用this赋值；
     * 而对源几次所有操作的组合源的操作标志为"源的原标志 & 源流标志的位掩码。"关于流源标志的位掩码我查看它的定义为：
     *
     * @param source      {@code Supplier<Spliterator>} describing the stream source
     * @param sourceFlags The source flags for the stream source, described in
     *                    {@link StreamOpFlag}
     * @param parallel    True if the pipeline is parallel
     */
    AbstractPipeline(Supplier<? extends Spliterator<?>> source,
                     int sourceFlags, boolean parallel) {
        this.previousStage = null;
        this.sourceSupplier = source;
        this.sourceStage = this;
        this.sourceOrOpFlags = sourceFlags & StreamOpFlag.STREAM_MASK;
        // The following is an optimization of:
        // StreamOpFlag.combineOpFlags(sourceOrOpFlags, StreamOpFlag.INITIAL_OPS_VALUE);
        this.combinedFlags = (~(sourceOrOpFlags << 1)) & StreamOpFlag.INITIAL_OPS_VALUE;
        this.depth = 0;
        this.parallel = parallel;
    }

    /**
     * Constructor for the head of a stream pipeline.
     * <p>
     * 对于流管道头部的构造函数：它着重的值有两个，一个是sourceOrOpFlags表示流的一系列中间操作。
     * 一个是combinedFlags表示流以及管道的所有操作(这里我有点不理解: 流管道的所有操作与初始值之后的按位与结果这两个具体是如何分工的?)
     *
     * @param source      {@code Spliterator} describing the stream source
     * @param sourceFlags the source flags for the stream source, described in
     *                    {@link StreamOpFlag}
     * @param parallel    {@code true} if the pipeline is parallel
     */
    AbstractPipeline(Spliterator<?> source,
                     int sourceFlags, boolean parallel) {
        this.previousStage = null;
        this.sourceSpliterator = source;
        this.sourceStage = this;
        this.sourceOrOpFlags = sourceFlags & StreamOpFlag.STREAM_MASK;
        // The following is an optimization of:
        // StreamOpFlag.combineOpFlags(sourceOrOpFlags, StreamOpFlag.INITIAL_OPS_VALUE);
        // 此管道中表示中间操作的值左移一位后的按位否定与管道中第一个流标志组合成的初始值的按位与结果
        this.combinedFlags = (~(sourceOrOpFlags << 1)) & StreamOpFlag.INITIAL_OPS_VALUE;
        // 此管道对象与流源之间的中间操作数（如果是顺序的），或之前的有状态if parallel.Valid在管道准备评估时。
        this.depth = 0;
        this.parallel = parallel;
    }

    /**
     * Constructor for appending an intermediate operation stage onto an
     * existing pipeline.
     *
     * @param previousStage the upstream pipeline stage
     * @param opFlags       the operation flags for the new stage, described in
     *                      {@link StreamOpFlag}
     */
    AbstractPipeline(AbstractPipeline<?, E_IN, ?> previousStage, int opFlags) {
        if (previousStage.linkedOrConsumed)
            throw new IllegalStateException(MSG_STREAM_LINKED);
        previousStage.linkedOrConsumed = true;
        previousStage.nextStage = this;

        this.previousStage = previousStage;
        this.sourceOrOpFlags = opFlags & StreamOpFlag.OP_MASK;
        this.combinedFlags = StreamOpFlag.combineOpFlags(opFlags, previousStage.combinedFlags);
        this.sourceStage = previousStage.sourceStage;
        if (opIsStateful())
            sourceStage.sourceAnyStateful = true;
        this.depth = previousStage.depth + 1;
    }


    // Terminal evaluation methods

    /**
     * Evaluate the pipeline with a terminal operation to produce a result.
     * <p>
     * 使用终端操作对管道进行评估以产生结果。
     *
     * @param <R>        the type of result
     * @param terminalOp the terminal operation to be applied to the pipeline.
     * @return the result
     */
    final <R> R evaluate(TerminalOp<E_OUT, R> terminalOp) {
        assert getOutputShape() == terminalOp.inputShape();
        if (linkedOrConsumed)
            throw new IllegalStateException(MSG_STREAM_LINKED);
        linkedOrConsumed = true;

        return isParallel()
                ? terminalOp.evaluateParallel(this, sourceSpliterator(terminalOp.getOpFlags()))
                : terminalOp.evaluateSequential(this, sourceSpliterator(terminalOp.getOpFlags()));
    }

    /**
     * Collect the elements output from the pipeline stage.
     *
     * @param generator the array generator to be used to create array instances
     * @return a flat array-backed Node that holds the collected output elements
     */
    @SuppressWarnings("unchecked")
    final Node<E_OUT> evaluateToArrayNode(IntFunction<E_OUT[]> generator) {
        if (linkedOrConsumed)
            throw new IllegalStateException(MSG_STREAM_LINKED);
        linkedOrConsumed = true;

        // If the last intermediate operation is stateful then
        // evaluate directly to avoid an extra collection step
        if (isParallel() && previousStage != null && opIsStateful()) {
            // Set the depth of this, last, pipeline stage to zero to slice the
            // pipeline such that this operation will not be included in the
            // upstream slice and upstream operations will not be included
            // in this slice
            depth = 0;
            return opEvaluateParallel(previousStage, previousStage.sourceSpliterator(0), generator);
        } else {
            return evaluate(sourceSpliterator(0), true, generator);
        }
    }

    /**
     * Gets the source stage spliterator if this pipeline stage is the source
     * stage.  The pipeline is consumed after this method is called and
     * returns successfully.
     *
     * @return the source stage spliterator
     * @throws IllegalStateException if this pipeline stage is not the source
     *                               stage.
     */
    @SuppressWarnings("unchecked")
    final Spliterator<E_OUT> sourceStageSpliterator() {
        if (this != sourceStage)
            throw new IllegalStateException();

        if (linkedOrConsumed)
            throw new IllegalStateException(MSG_STREAM_LINKED);
        linkedOrConsumed = true;

        if (sourceStage.sourceSpliterator != null) {
            @SuppressWarnings("unchecked")
            Spliterator<E_OUT> s = sourceStage.sourceSpliterator;
            sourceStage.sourceSpliterator = null;
            return s;
        } else if (sourceStage.sourceSupplier != null) {
            @SuppressWarnings("unchecked")
            Spliterator<E_OUT> s = (Spliterator<E_OUT>) sourceStage.sourceSupplier.get();
            sourceStage.sourceSupplier = null;
            return s;
        } else {
            throw new IllegalStateException(MSG_CONSUMED);
        }
    }

    // BaseStream

    @Override
    @SuppressWarnings("unchecked")
    public final S sequential() {
        sourceStage.parallel = false;
        return (S) this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public final S parallel() {
        sourceStage.parallel = true;
        return (S) this;
    }

    @Override
    public void close() {
        linkedOrConsumed = true;
        sourceSupplier = null;
        sourceSpliterator = null;
        if (sourceStage.sourceCloseAction != null) {
            Runnable closeAction = sourceStage.sourceCloseAction;
            sourceStage.sourceCloseAction = null;
            closeAction.run();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public S onClose(Runnable closeHandler) {
        if (linkedOrConsumed)
            throw new IllegalStateException(MSG_STREAM_LINKED);
        Objects.requireNonNull(closeHandler);
        Runnable existingHandler = sourceStage.sourceCloseAction;
        sourceStage.sourceCloseAction =
                (existingHandler == null)
                        ? closeHandler
                        : Streams.composeWithExceptions(existingHandler, closeHandler);
        return (S) this;
    }

    // Primitive specialization use co-variant overrides, hence is not final
    @Override
    @SuppressWarnings("unchecked")
    public Spliterator<E_OUT> spliterator() {
        if (linkedOrConsumed)
            throw new IllegalStateException(MSG_STREAM_LINKED);
        linkedOrConsumed = true;

        if (this == sourceStage) {
            if (sourceStage.sourceSpliterator != null) {
                @SuppressWarnings("unchecked")
                Spliterator<E_OUT> s = (Spliterator<E_OUT>) sourceStage.sourceSpliterator;
                sourceStage.sourceSpliterator = null;
                return s;
            } else if (sourceStage.sourceSupplier != null) {
                @SuppressWarnings("unchecked")
                Supplier<Spliterator<E_OUT>> s = (Supplier<Spliterator<E_OUT>>) sourceStage.sourceSupplier;
                sourceStage.sourceSupplier = null;
                return lazySpliterator(s);
            } else {
                throw new IllegalStateException(MSG_CONSUMED);
            }
        } else {
            return wrap(this, () -> sourceSpliterator(0), isParallel());
        }
    }

    @Override
    public final boolean isParallel() {
        return sourceStage.parallel;
    }


    /**
     * Returns the composition of stream flags of the stream source and all
     * intermediate operations.
     *
     * @return the composition of stream flags of the stream source and all
     * intermediate operations
     * @see StreamOpFlag
     */
    final int getStreamFlags() {
        return StreamOpFlag.toStreamFlags(combinedFlags);
    }

    /**
     * Get the source spliterator for this pipeline stage.  For a sequential or
     * stateless parallel pipeline, this is the source spliterator.  For a
     * stateful parallel pipeline, this is a spliterator describing the results
     * of all computations up to and including the most recent stateful
     * operation.
     */
    @SuppressWarnings("unchecked")
    private Spliterator<?> sourceSpliterator(int terminalFlags) {
        // Get the source spliterator of the pipeline
        Spliterator<?> spliterator = null;
        if (sourceStage.sourceSpliterator != null) {
            spliterator = sourceStage.sourceSpliterator;
            sourceStage.sourceSpliterator = null;
        } else if (sourceStage.sourceSupplier != null) {
            spliterator = (Spliterator<?>) sourceStage.sourceSupplier.get();
            sourceStage.sourceSupplier = null;
        } else {
            throw new IllegalStateException(MSG_CONSUMED);
        }

        if (isParallel() && sourceStage.sourceAnyStateful) {
            // Adapt the source spliterator, evaluating each stateful op
            // in the pipeline up to and including this pipeline stage.
            // The depth and flags of each pipeline stage are adjusted accordingly.
            int depth = 1;
            for (@SuppressWarnings("rawtypes") AbstractPipeline u = sourceStage, p = sourceStage.nextStage, e = this;
                 u != e;
                 u = p, p = p.nextStage) {

                int thisOpFlags = p.sourceOrOpFlags;
                if (p.opIsStateful()) {
                    depth = 0;

                    if (StreamOpFlag.SHORT_CIRCUIT.isKnown(thisOpFlags)) {
                        // Clear the short circuit flag for next pipeline stage
                        // This stage encapsulates short-circuiting, the next
                        // stage may not have any short-circuit operations, and
                        // if so spliterator.forEachRemaining should be used
                        // for traversal
                        thisOpFlags = thisOpFlags & ~StreamOpFlag.IS_SHORT_CIRCUIT;
                    }

                    spliterator = p.opEvaluateParallelLazy(u, spliterator);

                    // Inject or clear SIZED on the source pipeline stage
                    // based on the stage's spliterator
                    thisOpFlags = spliterator.hasCharacteristics(Spliterator.SIZED)
                            ? (thisOpFlags & ~StreamOpFlag.NOT_SIZED) | StreamOpFlag.IS_SIZED
                            : (thisOpFlags & ~StreamOpFlag.IS_SIZED) | StreamOpFlag.NOT_SIZED;
                }
                p.depth = depth++;
                p.combinedFlags = StreamOpFlag.combineOpFlags(thisOpFlags, u.combinedFlags);
            }
        }

        if (terminalFlags != 0) {
            // Apply flags from the terminal operation to last pipeline stage
            combinedFlags = StreamOpFlag.combineOpFlags(terminalFlags, combinedFlags);
        }

        return spliterator;
    }

    // PipelineHelper

    @Override
    final StreamShape getSourceShape() {
        @SuppressWarnings("rawtypes")
        AbstractPipeline p = AbstractPipeline.this;
        while (p.depth > 0) {
            p = p.previousStage;
        }
        return p.getOutputShape();
    }

    @Override
    final <P_IN> long exactOutputSizeIfKnown(Spliterator<P_IN> spliterator) {
        return StreamOpFlag.SIZED.isKnown(getStreamAndOpFlags()) ? spliterator.getExactSizeIfKnown() : -1;
    }

    @Override
    final <P_IN, S extends Sink<E_OUT>> S wrapAndCopyInto(S sink, Spliterator<P_IN> spliterator) {
        copyInto(wrapSink(Objects.requireNonNull(sink)), spliterator);
        return sink;
    }

    @Override
    final <P_IN> void copyInto(Sink<P_IN> wrappedSink, Spliterator<P_IN> spliterator) {
        Objects.requireNonNull(wrappedSink);

        if (!StreamOpFlag.SHORT_CIRCUIT.isKnown(getStreamAndOpFlags())) {
            wrappedSink.begin(spliterator.getExactSizeIfKnown());
            spliterator.forEachRemaining(wrappedSink);
            wrappedSink.end();
        } else {
            copyIntoWithCancel(wrappedSink, spliterator);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    final <P_IN> boolean copyIntoWithCancel(Sink<P_IN> wrappedSink, Spliterator<P_IN> spliterator) {
        @SuppressWarnings({"rawtypes", "unchecked"})
        AbstractPipeline p = AbstractPipeline.this;
        while (p.depth > 0) {
            p = p.previousStage;
        }

        wrappedSink.begin(spliterator.getExactSizeIfKnown());
        boolean cancelled = p.forEachWithCancel(spliterator, wrappedSink);
        wrappedSink.end();
        return cancelled;
    }

    @Override
    final int getStreamAndOpFlags() {
        return combinedFlags;
    }

    final boolean isOrdered() {
        return StreamOpFlag.ORDERED.isKnown(combinedFlags);
    }

    @Override
    @SuppressWarnings("unchecked")
    final <P_IN> Sink<P_IN> wrapSink(Sink<E_OUT> sink) {
        Objects.requireNonNull(sink);
        // 在for循环中，通过AbstractPipeline的depth参数判断还没有下一个，这个depth参数是，在开始生成Sink类的时候，就会让前面Sink的depth加1
        for (@SuppressWarnings("rawtypes") AbstractPipeline p = AbstractPipeline.this; p.depth > 0; p = p.previousStage) {
            sink = p.opWrapSink(p.previousStage.combinedFlags, sink);
        }
        return (Sink<P_IN>) sink;
    }

    @Override
    @SuppressWarnings("unchecked")
    final <P_IN> Spliterator<E_OUT> wrapSpliterator(Spliterator<P_IN> sourceSpliterator) {
        if (depth == 0) {
            return (Spliterator<E_OUT>) sourceSpliterator;
        } else {
            return wrap(this, () -> sourceSpliterator, isParallel());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    final <P_IN> Node<E_OUT> evaluate(Spliterator<P_IN> spliterator,
                                      boolean flatten,
                                      IntFunction<E_OUT[]> generator) {
        if (isParallel()) {
            // @@@ Optimize if op of this pipeline stage is a stateful op
            return evaluateToNode(this, spliterator, flatten, generator);
        } else {
            Node.Builder<E_OUT> nb = makeNodeBuilder(
                    exactOutputSizeIfKnown(spliterator), generator);
            return wrapAndCopyInto(nb, spliterator).build();
        }
    }


    // Shape-specific abstract methods, implemented by XxxPipeline classes

    /**
     * Get the output shape of the pipeline.  If the pipeline is the head,
     * then it's output shape corresponds to the shape of the source.
     * Otherwise, it's output shape corresponds to the output shape of the
     * associated operation.
     *
     * @return the output shape
     */
    abstract StreamShape getOutputShape();

    /**
     * Collect elements output from a pipeline into a Node that holds elements
     * of this shape.
     *
     * @param helper      the pipeline helper describing the pipeline stages
     * @param spliterator the source spliterator
     * @param flattenTree true if the returned node should be flattened
     * @param generator   the array generator
     * @return a Node holding the output of the pipeline
     */
    abstract <P_IN> Node<E_OUT> evaluateToNode(PipelineHelper<E_OUT> helper,
                                               Spliterator<P_IN> spliterator,
                                               boolean flattenTree,
                                               IntFunction<E_OUT[]> generator);

    /**
     * Create a spliterator that wraps a source spliterator, compatible with
     * this stream shape, and operations associated with a {@link
     * PipelineHelper}.
     *
     * @param ph       the pipeline helper describing the pipeline stages
     * @param supplier the supplier of a spliterator
     * @return a wrapping spliterator compatible with this shape
     */
    abstract <P_IN> Spliterator<E_OUT> wrap(PipelineHelper<E_OUT> ph,
                                            Supplier<Spliterator<P_IN>> supplier,
                                            boolean isParallel);

    /**
     * Create a lazy spliterator that wraps and obtains the supplied the
     * spliterator when a method is invoked on the lazy spliterator.
     *
     * @param supplier the supplier of a spliterator
     */
    abstract Spliterator<E_OUT> lazySpliterator(Supplier<? extends Spliterator<E_OUT>> supplier);

    /**
     * Traverse the elements of a spliterator compatible with this stream shape,
     * pushing those elements into a sink.   If the sink requests cancellation,
     * no further elements will be pulled or pushed.
     *
     * @param spliterator the spliterator to pull elements from
     * @param sink        the sink to push elements to
     * @return true if the cancellation was requested
     */
    abstract boolean forEachWithCancel(Spliterator<E_OUT> spliterator, Sink<E_OUT> sink);

    /**
     * Make a node builder compatible with this stream shape.
     *
     * @param exactSizeIfKnown if {@literal >=0}, then a node builder will be
     *                         created that has a fixed capacity of at most sizeIfKnown elements. If
     *                         {@literal < 0}, then the node builder has an unfixed capacity. A fixed
     *                         capacity node builder will throw exceptions if an element is added after
     *                         builder has reached capacity, or is built before the builder has reached
     *                         capacity.
     * @param generator        the array generator to be used to create instances of a
     *                         T[] array. For implementations supporting primitive nodes, this parameter
     *                         may be ignored.
     * @return a node builder
     */
    @Override
    abstract Node.Builder<E_OUT> makeNodeBuilder(long exactSizeIfKnown,
                                                 IntFunction<E_OUT[]> generator);


    // Op-specific abstract methods, implemented by the operation class

    /**
     * Returns whether this operation is stateful or not.  If it is stateful,
     * then the method
     * {@link #opEvaluateParallel(PipelineHelper, Spliterator, IntFunction)}
     * must be overridden.
     *
     * @return {@code true} if this operation is stateful
     */
    abstract boolean opIsStateful();

    /**
     * Accepts a {@code Sink} which will receive the results of this operation,
     * and return a {@code Sink} which accepts elements of the input type of
     * this operation and which performs the operation, passing the results to
     * the provided {@code Sink}.
     * <p>
     * 接收接收将接收此操作的结果的接收器，
     * 并返回接收此操作的输入类型的元素并执行此操作的接收器，将结果传递给提供的接收器。
     * <p>
     * Steam就是按照这种方式，先顺序执行所有的begin（），然后所有accept（），然后所有end()，
     * 然后所有的cancellationRequested（）方法
     *
     * @param flags The combined stream and operation flags up to, but not
     *              including, this operation
     * @param sink  sink to which elements should be sent after processing
     * @return a sink which accepts elements, perform the operation upon
     * each element, and passes the results (if any) to the provided
     * {@code Sink}.
     * @apiNote The implementation may use the {@code flags} parameter to optimize the
     * sink wrapping.  For example, if the input is already {@code DISTINCT},
     * the implementation for the {@code Stream#distinct()} method could just
     * return the sink it was passed.
     */
    abstract Sink<E_IN> opWrapSink(int flags, Sink<E_OUT> sink);

    /**
     * Performs a parallel evaluation of the operation using the specified
     * {@code PipelineHelper} which describes the upstream intermediate
     * operations.  Only called on stateful operations.  If {@link
     * #opIsStateful()} returns true then implementations must override the
     * default implementation.
     * <p>
     * 使用描述上游中间操作的指定PipelineHelper执行操作的并行计算。
     * 只调用有状态操作。如果opIsStateful()返回true，则实现必须覆盖默认实现。
     *
     * @param helper      the pipeline helper describing the pipeline stages
     * @param spliterator the source {@code Spliterator}
     * @param generator   the array generator
     * @return a {@code Node} describing the result of the evaluation
     * @implSpec The default implementation always throw
     * {@code UnsupportedOperationException}.
     */
    <P_IN> Node<E_OUT> opEvaluateParallel(PipelineHelper<E_OUT> helper,
                                          Spliterator<P_IN> spliterator,
                                          IntFunction<E_OUT[]> generator) {
        throw new UnsupportedOperationException("Parallel evaluation is not supported");
    }

    /**
     * Returns a {@code Spliterator} describing a parallel evaluation of the
     * operation, using the specified {@code PipelineHelper} which describes the
     * upstream intermediate operations.  Only called on stateful operations.
     * It is not necessary (though acceptable) to do a full computation of the
     * result here; it is preferable, if possible, to describe the result via a
     * lazily evaluated spliterator.
     *
     * @param helper      the pipeline helper
     * @param spliterator the source {@code Spliterator}
     * @return a {@code Spliterator} describing the result of the evaluation
     * @implSpec The default implementation behaves as if:
     * <pre>{@code
     *     return evaluateParallel(helper, i -> (E_OUT[]) new
     * Object[i]).spliterator();
     * }</pre>
     * and is suitable for implementations that cannot do better than a full
     * synchronous evaluation.
     */
    @SuppressWarnings("unchecked")
    <P_IN> Spliterator<E_OUT> opEvaluateParallelLazy(PipelineHelper<E_OUT> helper,
                                                     Spliterator<P_IN> spliterator) {
        return opEvaluateParallel(helper, spliterator, i -> (E_OUT[]) new Object[i]).spliterator();
    }
}
