package hm.binkley.util.stream;

import hm.binkley.util.function.ThrowingBiFunction;
import hm.binkley.util.function.ThrowingBinaryOperator;
import hm.binkley.util.function.ThrowingBooleanSupplier;
import hm.binkley.util.function.ThrowingConsumer;
import hm.binkley.util.function.ThrowingFunction;
import hm.binkley.util.function.ThrowingLongSupplier;
import hm.binkley.util.function.ThrowingPredicate;
import hm.binkley.util.function.ThrowingRunnable;
import hm.binkley.util.function.ThrowingSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Spliterator;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import java.util.stream.Collector;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.lang.Thread.currentThread;
import static java.util.stream.Collectors.toList;

/**
 * {@code CheckedStream} is a <em>throwing</em> {@link Stream} look-a-like.  It cannot be a {@code
 * Stream} as it takes throwing versions of suppliers, functions and consumers. Otherwise it is a
 * faithful reproduction.
 * <p>
 * "Intentional" exceptions (checked exceptions plus {@code CancellationException}) have "scrubbed"
 * stacktraces: frames from this class or from the {@code mbl.labs.util.function} and {@code
 * java.util} packages are removed before the intentional exception is rethrown to calling code.
 * Scrubbed stacktraces are much easier to understand, the framework and glue code having been
 * removed.
 * <p>
 * To see the unscrubbed stacktrace, set the system property "mbl.labs.util.stream.CheckedStream.debug"
 * to "true".
 *
 * @author <a href="mailto:binkley@alumni.rice.edu">B. K. Oxley (binkley)</a>
 * @todo Optimize non-throwing, non-terminal calls: they don't need wrapping
 */
@SuppressWarnings({"UnusedDeclaration", "JavaDoc"})
public abstract class CheckedStream<T>
        implements AutoCloseable {
    private static final String className = CheckedStream.class.getName();
    private static final String innerName = className + "$";
    private static final String funcName = ThrowingFunction.class.getPackage().getName();
    private static final String javaName = "java.util.";
    private static final boolean debug = Boolean.getBoolean(className + ".debug");
    private final Stream<T> delegate;
    private final StreamException thrown;

    /**
     * Creates a new sequential {@code CheckedStream} wrapping the given <var>delegate</var>
     * stream.
     *
     * @param delegate the delegated stream, never missing
     * @param <T> the stream element type
     *
     * @return the new checked stream, never missing
     */
    @Nonnull
    public static <T> CheckedStream<T> checked(@Nonnull final Stream<T> delegate) {
        return new SequentialCheckedStream<>(delegate, new StreamException());
    }

    /**
     * Creates a new parallel {@code CheckedStream} wrapping the given <var>delegate</var> stream
     * and executing on <var>threads</var>.
     *
     * @param delegate the delegated stream, never missing
     * @param threads the fork-join thread pool, never missing
     * @param <T> the stream element type
     *
     * @return the new checked stream, never missing
     */
    @Nonnull
    public static <T> CheckedStream<T> checked(@Nonnull final Stream<T> delegate,
            @Nonnull final ForkJoinPool threads) {
        return new ParallelCheckedStream<>(delegate, new StreamException(), threads);
    }

    /**
     * Constructs a new {@code CheckedStream} for the given <var>delegate</var> stream and
     * <var>thrown</var> exception holder.
     *
     * @param delegate the delegated stream, never missing
     * @param thrown the exception holder, never missing
     */
    protected CheckedStream(@Nonnull final Stream<T> delegate,
            @Nonnull final StreamException thrown) {
        this.delegate = delegate;
        this.thrown = thrown;
    }

    @Nonnull
    protected abstract <U> CheckedStream<U> next(@Nonnull final Stream<U> delegate,
            @Nonnull final StreamException thrown);

    @Nonnull
    protected abstract CheckedStream<T> sequential(@Nonnull final StreamException thrown)
            throws Exception;

    @Nonnull
    protected abstract CheckedStream<T> parallel(@Nonnull final StreamException thrown,
            @Nonnull final ForkJoinPool threads)
            throws Exception;

    protected abstract <E extends Exception> void terminateVoid(final ThrowingRunnable<E> action)
            throws E, InterruptedException;

    protected abstract <U, E extends Exception> U terminateConcrete(
            final ThrowingSupplier<U, E> supplier)
            throws E, InterruptedException;

    protected abstract <E extends Exception> long terminateLong(
            final ThrowingLongSupplier<E> supplier)
            throws E, InterruptedException;

    protected abstract <E extends Exception> boolean terminateBoolean(
            final ThrowingBooleanSupplier<E> supplier)
            throws E, InterruptedException;

    /** Gets the delegated stream. */
    @Nonnull
    public final Stream<T> asStream() {
        return delegate;
    }

    /** @see Stream#iterator() */
    @Nonnull
    public final <E extends Exception> Iterator<T> iterator()
            throws E, InterruptedException {
        return this.<Iterator<T>, E>terminateConcrete(() -> evaluateConcrete(delegate::iterator));
    }

    /** @see Stream#spliterator() */
    @Nonnull
    public final <E extends Exception> Spliterator<T> spliterator()
            throws E, InterruptedException {
        return this.<Spliterator<T>, E>terminateConcrete(
                () -> evaluateConcrete(delegate::spliterator));
    }

    /** @see Stream#isParallel() */
    public final boolean isParallel() {
        return delegate.isParallel();
    }

    /** @see Stream#sequential() */
    @Nonnull
    public final CheckedStream<T> sequential()
            throws Exception {
        return sequential(thrown);
    }

    /** @see Stream#parallel() */
    @Nonnull
    public final CheckedStream<T> parallel(@Nonnull final ForkJoinPool threads)
            throws Exception {
        return parallel(thrown, threads);
    }

    /** @see Stream#unordered() */
    @Nonnull
    public final <E extends Exception> CheckedStream<T> unordered()
            throws E, InterruptedException {
        return evaluateStream(delegate::unordered);
    }

    /** @see Stream#onClose(Runnable) */
    @Nonnull
    public final CheckedStream<T> onClose(@Nonnull final ThrowingRunnable<?> closeHandler) {
        return next(delegate.onClose(asRunnable(closeHandler)), thrown);
    }

    /** @see Stream#filter(Predicate) */
    @Nonnull
    public final <E extends Exception> CheckedStream<T> filter(
            @Nonnull final ThrowingPredicate<? super T, E> predicate)
            throws E, InterruptedException {
        return evaluateStream(() -> delegate.filter(asPredicate(predicate)));
    }

    /** @see Stream#map(Function) */
    @Nonnull
    public final <R, E extends Exception> CheckedStream<R> map(
            @Nonnull final ThrowingFunction<? super T, ? extends R, E> mapper)
            throws E, InterruptedException {
        return evaluateStream(() -> delegate.map(asFunction(mapper)));
    }

    /**
     * @todo Throwing version
     * @see Stream#mapToInt(ToIntFunction)
     */
    @Nonnull
    public final <E extends Exception> IntStream mapToInt(@Nonnull final ToIntFunction<? super T> mapper)
            throws E, InterruptedException {
        return evaluateConcrete(() -> delegate.mapToInt(mapper));
    }

    /**
     * @todo Throwing version
     * @see Stream#mapToLong(ToLongFunction)
     */
    @Nonnull
    public final <E extends Exception> LongStream mapToLong(
            @Nonnull final ToLongFunction<? super T> mapper)
            throws E, InterruptedException {
        return evaluateConcrete(() -> delegate.mapToLong(mapper));
    }

    /**
     * @todo Throwing version
     * @see Stream#mapToDouble(ToDoubleFunction)
     */
    @Nonnull
    public final <E extends Exception> DoubleStream mapToDouble(
            @Nonnull final ToDoubleFunction<? super T> mapper)
            throws E, InterruptedException {
        return evaluateConcrete(() -> delegate.mapToDouble(mapper));
    }

    /** @see Stream#flatMap(Function) */
    @Nonnull
    public final <R, E extends Exception> CheckedStream<R> flatMap(@Nonnull
    final ThrowingFunction<? super T, ? extends Stream<? extends R>, E> mapper)
            throws E, InterruptedException {
        return evaluateStream(() -> delegate.flatMap(asFunction(mapper)));
    }

    /**
     * @todo Throwing version
     * @see Stream#flatMapToInt(Function)
     */
    @Nonnull
    public final <E extends Exception> IntStream flatMapToInt(
            @Nonnull final Function<? super T, ? extends IntStream> mapper)
            throws E, InterruptedException {
        return evaluateConcrete(() -> delegate.flatMapToInt(mapper));
    }

    /**
     * @todo Throwing version
     * @see Stream#flatMapToLong(Function)
     */
    @Nonnull
    public final <E extends Exception> LongStream flatMapToLong(
            @Nonnull final Function<? super T, ? extends LongStream> mapper)
            throws E, InterruptedException {
        return evaluateConcrete(() -> delegate.flatMapToLong(mapper));
    }

    /**
     * @todo Throwing version
     * @see Stream#flatMapToDouble(Function)
     */
    @Nonnull
    public final <E extends Exception> DoubleStream flatMapToDouble(
            @Nonnull final Function<? super T, ? extends DoubleStream> mapper)
            throws E, InterruptedException {
        return evaluateConcrete(() -> delegate.flatMapToDouble(mapper));
    }

    /** @see Stream#distinct() */
    @Nonnull
    public final <E extends Exception> CheckedStream<T> distinct()
            throws E, InterruptedException {
        return evaluateStream(delegate::distinct);
    }

    /** @see Stream#sorted() */
    @Nonnull
    public final <E extends Exception> CheckedStream<T> sorted()
            throws E, InterruptedException {
        return this.<CheckedStream<T>, E>terminateConcrete(() -> evaluateStream(delegate::sorted));
    }

    /** @see Stream#sorted(Comparator) */
    @Nonnull
    public final <E extends Exception> CheckedStream<T> sorted(
            @Nonnull final Comparator<? super T> comparator)
            throws E, InterruptedException {
        return this.<CheckedStream<T>, E>terminateConcrete(
                () -> evaluateStream(() -> delegate.sorted(comparator)));
    }

    /** @see Stream#peek(Consumer) */
    @Nonnull
    public final <E extends Exception> CheckedStream<T> peek(
            @Nonnull final ThrowingConsumer<? super T, E> action)
            throws E, InterruptedException {
        return evaluateStream(() -> delegate.peek(asConsumer(action)));
    }

    /** @see Stream#limit(long) */
    @Nonnull
    public final <E extends Exception> CheckedStream<T> limit(final long maxSize)
            throws E, InterruptedException {
        return evaluateStream(() -> delegate.limit(maxSize));
    }

    /** @see Stream#skip(long) */
    @Nonnull
    public final <E extends Exception> CheckedStream<T> skip(final long n)
            throws E, InterruptedException {
        return evaluateStream(() -> delegate.skip(n));
    }

    /** @see Stream#forEach(Consumer) */
    public final <E extends Exception> void forEach(
            @Nonnull final ThrowingConsumer<? super T, E> action)
            throws InterruptedException {
        terminateVoid(() -> evaluateVoid(() -> delegate.forEach(asConsumer(action))));
    }

    /** @see Stream#forEachOrdered(Consumer) */
    public final <E extends Exception> void forEachOrdered(
            @Nonnull final ThrowingConsumer<? super T, E> action)
            throws E, InterruptedException {
        terminateVoid(() -> evaluateVoid(() -> delegate.forEachOrdered(asConsumer(action))));
    }

    /** @see Stream#toArray() */
    @Nonnull
    public final <E extends Exception> Object[] toArray()
            throws E, InterruptedException {
        return this.<Object[], E>terminateConcrete(() -> evaluateConcrete(delegate::toArray));
    }

    /** @see Stream#toArray(IntFunction) */
    @Nonnull
    public final <A, E extends Exception> A[] toArray(@Nonnull final IntFunction<A[]> generator)
            throws E, InterruptedException {
        return this.<A[], E>terminateConcrete(
                () -> evaluateConcrete(() -> delegate.toArray(generator)));
    }

    /**
     * @see Stream#reduce(Object, BiFunction, BinaryOperator)
     */
    public final <E extends Exception> T reduce(@Nonnull final T identity,
            @Nonnull final ThrowingBinaryOperator<T, E> accumulator)
            throws E, InterruptedException {
        return this.<T, E>terminateConcrete(() -> evaluateConcrete(
                () -> delegate.reduce(identity, asBinaryOperator(accumulator))));
    }

    /** @see Stream#reduce(BinaryOperator) */
    @Nonnull
    public final <E extends Exception> Optional<T> reduce(
            @Nonnull final ThrowingBinaryOperator<T, E> accumulator)
            throws E, InterruptedException {
        return this.<Optional<T>, E>terminateConcrete(
                () -> evaluateConcrete(() -> delegate.reduce(asBinaryOperator(accumulator))));
    }

    /** @see Stream#reduce(Object, BinaryOperator) */
    public final <U, E extends Exception> U reduce(@Nullable final U identity,
            @Nonnull final ThrowingBiFunction<U, ? super T, U, E> accumulator,
            @Nonnull final ThrowingBinaryOperator<U, E> combiner)
            throws E, InterruptedException {
        return this.<U, E>terminateConcrete(() -> evaluateConcrete(() -> delegate
                .reduce(identity, asBiFunction(accumulator), asBinaryOperator(combiner))));
    }

    /**
     * @todo Throwing version
     * @see Stream#collect(Supplier, BiConsumer, BiConsumer)
     */
    @Nonnull
    public final <R, E extends Exception> R collect(@Nonnull final Supplier<R> supplier,
            @Nonnull final BiConsumer<R, ? super T> accumulator,
            @Nonnull final BiConsumer<R, R> combiner)
            throws E, InterruptedException {
        return this.<R, E>terminateConcrete(
                () -> evaluateConcrete(() -> delegate.collect(supplier, accumulator, combiner)));
    }

    /**
     * @todo Throwing version
     * @see Stream#collect(Collector)
     */
    @Nonnull
    public final <R, A, E extends Exception> R collect(
            @Nonnull final Collector<? super T, A, R> collector)
            throws E, InterruptedException {
        return this
                .<R, E>terminateConcrete(() -> evaluateConcrete(() -> delegate.collect(collector)));
    }

    /** @see Stream#min(Comparator) */
    @Nonnull
    public final <E extends Exception> Optional<T> min(@Nonnull final Comparator<? super T> comparator)
            throws E, InterruptedException {
        return this.<Optional<T>, E>terminateConcrete(
                () -> evaluateConcrete(() -> delegate.min(comparator)));
    }

    /** @see Stream#max(Comparator) */
    @Nonnull
    public final <E extends Exception> Optional<T> max(@Nonnull final Comparator<? super T> comparator)
            throws E, InterruptedException {
        return this.<Optional<T>, E>terminateConcrete(
                () -> evaluateConcrete(() -> delegate.max(comparator)));
    }

    /** @see Stream#count() */
    public final long count()
            throws InterruptedException {
        return terminateLong(() -> evaluateLong(delegate::count));
    }

    /** @see Stream#anyMatch(Predicate) */
    public final <E extends Exception> boolean anyMatch(
            @Nonnull final ThrowingPredicate<? super T, E> predicate)
            throws E, InterruptedException {
        return evaluateBoolean(() -> delegate.anyMatch(asPredicate(predicate)));
    }

    /** @see Stream#allMatch(Predicate) */
    public final <E extends Exception> boolean allMatch(
            @Nonnull final ThrowingPredicate<? super T, E> predicate)
            throws E, InterruptedException {
        return evaluateBoolean(() -> delegate.allMatch(asPredicate(predicate)));
    }

    /** @see Stream#noneMatch(Predicate) */
    public final <E extends Exception> boolean noneMatch(
            @Nonnull final ThrowingPredicate<? super T, E> predicate)
            throws E, InterruptedException {
        return evaluateBoolean(() -> delegate.noneMatch(asPredicate(predicate)));
    }

    /** @see Stream#findFirst() */
    @Nonnull
    public final <E extends Exception> Optional<T> findFirst()
            throws E, InterruptedException {
        return this.<Optional<T>, E>terminateConcrete(() -> evaluateConcrete(delegate::findFirst));
    }

    /** @see Stream#findAny() */
    @Nonnull
    public final <E extends Exception> Optional<T> findAny()
            throws E, InterruptedException {
        return this.<Optional<T>, E>terminateConcrete(() -> evaluateConcrete(delegate::findAny));
    }

    /**
     * Closes the delegated stream.
     *
     * @throws Exception if any registered {@link #onClose(ThrowingRunnable)
     * close handlers} throw
     */
    @Override
    public final void close()
            throws Exception {
        terminateVoid(() -> evaluateVoid(delegate::close));
    }

    // Implementation

    protected final Stream<T> immediate()
            throws Exception {
        if (true) return delegate.collect(toList()).stream();
        // TODO: Why doesn't spliterator work like this?  Javadoc says it's terminal
        return StreamSupport.stream(spliterator(), isParallel());
    }

    private <E extends Exception> Predicate<T> asPredicate(
            final ThrowingPredicate<? super T, E> predicate) {
        return t -> defer(() -> predicate.test(t));
    }

    private <R, E extends Exception> Function<T, R> asFunction(
            final ThrowingFunction<? super T, ? extends R, E> mapper) {
        return t -> defer(() -> mapper.apply(t));
    }

    private <R, U, V, E extends Exception> BiFunction<U, V, R> asBiFunction(
            final ThrowingBiFunction<? super U, ? super V, ? extends R, E> mapper) {
        return (u, v) -> defer(() -> mapper.apply(u, v));
    }

    private <U, E extends Exception> BinaryOperator<U> asBinaryOperator(
            final ThrowingBinaryOperator<U, E> mapper) {
        return (u, v) -> defer(() -> mapper.apply(u, v));
    }

    private <E extends Exception> Consumer<T> asConsumer(
            final ThrowingConsumer<? super T, E> action) {
        return t -> defer(() -> action.accept(t));
    }

    private <R, E extends Exception> Supplier<R> asSupplier(
            final ThrowingSupplier<? extends R, E> supplier) {
        return () -> defer(supplier);
    }

    private <E extends Exception> BooleanSupplier asBooleanSupplier(
            final ThrowingBooleanSupplier<E> supplier) {
        return () -> defer(supplier);
    }

    private <E extends Exception> Runnable asRunnable(final ThrowingRunnable<E> runnable) {
        return () -> defer(runnable);
    }

    private <R, E extends Exception> R defer(final ThrowingSupplier<? extends R, E> frame) {
        try {
            return frame.get();
        } catch (final CancellationException e) {
            throw thrown.defer(e);
        } catch (final RuntimeException e) {
            throw e;
        } catch (final InterruptedException e) {
            currentThread().interrupt();
            throw thrown.defer(e);
        } catch (final Exception e) {
            throw thrown.defer(e);
        }
    }

    private <E extends Exception> boolean defer(final ThrowingBooleanSupplier<E> frame) {
        try {
            return frame.getAsBoolean();
        } catch (final CancellationException e) {
            throw thrown.defer(e);
        } catch (final RuntimeException e) {
            throw e;
        } catch (final InterruptedException e) {
            currentThread().interrupt();
            throw thrown.defer(e);
        } catch (final Exception e) {
            throw thrown.defer(e);
        }
    }

    private <E extends Exception> void defer(final ThrowingRunnable<E> frame) {
        try {
            frame.run();
        } catch (final CancellationException e) {
            throw thrown.defer(e);
        } catch (final RuntimeException e) {
            throw e;
        } catch (final InterruptedException e) {
            currentThread().interrupt();
            throw thrown.defer(e);
        } catch (final Exception e) {
            throw thrown.defer(e);
        }
    }

    private <R, E extends Exception> CheckedStream<R> evaluateStream(
            final Supplier<Stream<R>> frame)
            throws E, InterruptedException {
        try {
            return next(frame.get(), thrown);
        } catch (final StreamException thrown) {
            throw thrown.<E>cast();
        }
    }

    private <U, E extends Exception> U evaluateConcrete(final Supplier<U> frame)
            throws E, InterruptedException {
        try {
            return frame.get();
        } catch (final StreamException thrown) {
            throw thrown.<E>cast();
        }
    }

    private <E extends Exception> void evaluateVoid(final Runnable frame)
            throws E, InterruptedException {
        try {
            frame.run();
        } catch (final StreamException thrown) {
            throw thrown.<E>cast();
        }
    }

    private <E extends Exception> boolean evaluateBoolean(final BooleanSupplier supplier)
            throws E, InterruptedException {
        try {
            return supplier.getAsBoolean();
        } catch (final StreamException thrown) {
            throw thrown.<E>cast();
        }
    }

    private <E extends Exception> long evaluateLong(final LongSupplier supplier)
            throws E, InterruptedException {
        try {
            return supplier.getAsLong();
        } catch (final StreamException thrown) {
            throw thrown.<E>cast();
        }
    }

    protected static final class StreamException
            extends RuntimeException {
        public StreamException defer(final Exception e) {
            addSuppressed(e); // TODO: Race condition - is suppressed concurrent?
            return this;
        }

        @SuppressWarnings("unchecked")
        public <E extends Exception> E cast()
                throws InterruptedException {
            final Throwable[] suppressed = getSuppressed();
            final Throwable x = suppressed[0];
            for (int i = 1; i < suppressed.length; ++i)
                x.addSuppressed(suppressed[i]);
            if (x instanceof CancellationException)
                throw scrub((CancellationException) x);
            if (x instanceof InterruptedException) {
                currentThread().interrupt();
                throw scrub((InterruptedException) x);
            }
            return scrub((E) x);
        }

        private <E extends Exception> E scrub(final E e) {
            if (debug)
                return e;

            final StackTraceElement[] stack = e.getStackTrace();
            final List<StackTraceElement> scrubbed = new ArrayList<>(stack.length);
            for (final StackTraceElement element : stack) {
                final String frameName = element.getClassName();
                if (className.equals(frameName))
                    continue;
                if (frameName.startsWith(innerName))
                    continue;
                if (frameName.startsWith(funcName))
                    continue;
                if (frameName.startsWith(javaName))
                    continue;
                scrubbed.add(element);
            }
            e.setStackTrace(scrubbed.toArray(new StackTraceElement[scrubbed.size()]));

            return e;
        }
    }

    private static final class SequentialCheckedStream<T>
            extends CheckedStream<T> {
        private SequentialCheckedStream(@Nonnull final Stream<T> delegate,
                @Nonnull final StreamException thrown) {
            super(delegate, thrown);
        }

        @Nonnull
        @Override
        protected <U> CheckedStream<U> next(@Nonnull final Stream<U> delegate,
                @Nonnull final StreamException thrown) {
            return new SequentialCheckedStream<>(delegate, thrown);
        }

        @Nonnull
        @Override
        protected CheckedStream<T> sequential(@Nonnull final StreamException thrown)
                throws Exception {
            return this;
        }

        @Nonnull
        @Override
        protected CheckedStream<T> parallel(@Nonnull final StreamException thrown,
                @Nonnull final ForkJoinPool threads)
                throws Exception {
            return new ParallelCheckedStream<>(immediate().parallel(), thrown, threads);
        }

        @Override
        protected <E extends Exception> void terminateVoid(final ThrowingRunnable<E> action)
                throws E, InterruptedException {
            action.run();
        }

        @Override
        protected <U, E extends Exception> U terminateConcrete(
                final ThrowingSupplier<U, E> supplier)
                throws E, InterruptedException {
            return supplier.get();
        }

        @Override
        protected <E extends Exception> long terminateLong(final ThrowingLongSupplier<E> supplier)
                throws E, InterruptedException {
            return supplier.getAsLong();
        }

        @Override
        protected <E extends Exception> boolean terminateBoolean(
                final ThrowingBooleanSupplier<E> supplier)
                throws E, InterruptedException {
            return supplier.getAsBoolean();
        }
    }

    private static final class ParallelCheckedStream<T>
            extends CheckedStream<T> {
        private final ForkJoinPool threads;

        private ParallelCheckedStream(@Nonnull final Stream<T> delegate,
                @Nonnull final StreamException thrown, final ForkJoinPool threads) {
            super(delegate, thrown);
            this.threads = threads;
        }

        @Nonnull
        @Override
        protected <U> CheckedStream<U> next(@Nonnull final Stream<U> delegate,
                @Nonnull final StreamException thrown) {
            return new ParallelCheckedStream<>(delegate, thrown, threads);
        }

        @Nonnull
        @Override
        protected CheckedStream<T> sequential(@Nonnull final StreamException thrown)
                throws Exception {
            return new SequentialCheckedStream<>(immediate().sequential(), thrown);
        }

        @Nonnull
        @Override
        protected CheckedStream<T> parallel(@Nonnull final StreamException thrown,
                @Nonnull final ForkJoinPool threads)
                throws Exception {
            return this.threads.equals(threads) ? this
                    : new ParallelCheckedStream<>(immediate().parallel(), thrown, threads);
        }

        @Override
        protected <E extends Exception> void terminateVoid(final ThrowingRunnable<E> action)
                throws E, InterruptedException {
            try {
                threads.submit(() -> {
                    action.run();
                    return null;
                }).get();
            } catch (final ExecutionException e) {
                throw ParallelCheckedStream.<E>handleForkJoinPool(e);
            }
        }

        @Override
        protected <U, E extends Exception> U terminateConcrete(
                final ThrowingSupplier<U, E> supplier)
                throws E, InterruptedException {
            try {
                return threads.submit(supplier::get).get();
            } catch (final ExecutionException e) {
                throw ParallelCheckedStream.<E>handleForkJoinPool(e);
            }
        }

        @Override
        protected <E extends Exception> long terminateLong(final ThrowingLongSupplier<E> supplier)
                throws E, InterruptedException {
            try {
                return threads.submit(supplier::getAsLong).get();
            } catch (final ExecutionException e) {
                throw ParallelCheckedStream.<E>handleForkJoinPool(e);
            }
        }

        @Override
        protected <E extends Exception> boolean terminateBoolean(
                final ThrowingBooleanSupplier<E> supplier)
                throws E, InterruptedException {
            try {
                return threads.submit(supplier::getAsBoolean).get();
            } catch (final ExecutionException e) {
                throw ParallelCheckedStream.<E>handleForkJoinPool(e);
            }
        }

        /**
         * Tricky, guts of FJP wrap our checked in a runtime, wraps tha in an execution, but user
         * runtime wraps nothing - check stacktrace for thrown by ForkJoinTask
         */
        private static <E extends Exception> E handleForkJoinPool(final ExecutionException e) {
            final Throwable x = e.getCause();
            if (RuntimeException.class != x.getClass())
                return (E) x;
            final Throwable y = x.getCause();
            if (null == y || !x.getStackTrace()[0].getClassName()
                    .startsWith(ForkJoinTask.class.getName()))
                throw (RuntimeException) x;
            return (E) y;
        }
    }
}