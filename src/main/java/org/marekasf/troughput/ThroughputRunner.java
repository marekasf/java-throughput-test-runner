/*
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package org.marekasf.troughput;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.marekasf.troughput.histogram.AdaptiveHistogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Scheduler;
import rx.functions.Action0;
import rx.schedulers.Schedulers;

public class ThroughputRunner
{
	private static final Logger LOG = LoggerFactory.getLogger(ThroughputRunner.class);

	protected volatile String lastLog;

	public static final BiConsumer<String, Throwable> SYSOUT = (t, e) -> {
		if (e == null)
		{
			System.out.println(t);
		}
		else
		{
			System.out.println(t + " > " + e.getMessage());
			e.printStackTrace();
		}
	};

	private final BiConsumer<String, Throwable> printer;

	public ThroughputRunner(final BiConsumer<String, Throwable> printer)
	{
		this.printer = printer;
	}

	public static Builder ofAction(final Supplier<Observable<?>> action)
	{
		return Builder.create(action);
	}

	public class Daemon
	{
		private Action0 runner;
		private volatile boolean started = false;
		private volatile long start;

		private Daemon(final Action0 runner)
		{
			this.runner = runner;
		}

		public String log()
		{
			return ThroughputRunner.this.lastLog;
		}

		public String histogram()
		{
			return ThroughputRunner.this.printHistogram();
		}

		public String errors()
		{
			return ThroughputRunner.this.printErrors();
		}

		public String stats()
		{
			return ThroughputRunner.this.printStats(Math.max((int) (System.currentTimeMillis() - start) / 1000, 1));
		}

		public void start()
		{
			synchronized (this)
			{
				if (!started)
				{
					final Scheduler.Worker worker = Schedulers.io().createWorker();
					worker.schedule(runner);
					this.start = System.currentTimeMillis();
					this.started = true;
				}
			}
		}

		public void stop()
		{
			synchronized (this)
			{
				if (started)
				{
					ThroughputRunner.this.stop();
				}
			}
		}
	}

	public static class Builder
	{
		private Supplier<Observable<?>> action;
		private int threads = 1;
		private int testTimeInSeconds = 10;
		private boolean histogram = true;
		private boolean histogramGraph = true;
		private BiConsumer<String, Throwable> printer = LOG::error;
		private int logSleepSeconds = 1;
		private boolean stress = true;

		public Builder action(final Supplier<Observable<?>> action)
		{
			this.action = action;
			return this;
		}

		public Builder(final Supplier<Observable<?>> action)
		{
			this.action = action;
		}

		public static Builder create(final Supplier<Observable<?>> action)
		{
			return new Builder(action);
		}

		public static Builder create()
		{
			return new Builder(null);
		}

		public Builder threads(final int threads)
		{
			this.threads = threads;
			return this;
		}

		public Builder testTimeInSeconds(final int testTimeInSeconds)
		{
			this.testTimeInSeconds = testTimeInSeconds;
			return this;
		}

		public Builder histogram(final boolean display)
		{
			this.histogram = display;
			return this;
		}

		public Builder graph(final boolean display)
		{
			this.histogramGraph = display;
			return this;
		}

		public Builder printer(final BiConsumer<String, Throwable> printer)
		{
			this.printer = printer;
			return this;
		}

		public Builder logSleepSeconds(final int logSleepSeconds)
		{
			this.logSleepSeconds = logSleepSeconds;
			return this;
		}

		public Builder stress(final boolean stress)
		{
			this.stress = stress;
			return this;
		}

		private void performance(final ThroughputRunner throughputRunner)
		{
			try
			{
				throughputRunner.performance(threads, testTimeInSeconds, action, histogram, histogramGraph, logSleepSeconds,
						stress);
			}
			catch (InterruptedException e)
			{
				throw new RuntimeException("Performance test interrupted", e);
			}
		}

		public void run()
		{
			final ThroughputRunner throughputRunner = new ThroughputRunner(printer);
			performance(throughputRunner);
		}

		public Daemon daemon()
		{
			final ThroughputRunner throughputRunner = new ThroughputRunner(printer);
			return throughputRunner.daemon(() -> {
				try
				{
					performance(throughputRunner);
				}
				catch (Throwable t)
				{
					LOG.error("\n\n\n\nDAEMON FAILED", t);
				}
			});
		}
	}

	private Daemon daemon(final Action0 runner)
	{
		return new Daemon(runner);
	}

	private final LongAdder requestCount = new LongAdder();
	private final LongAdder loopCount = new LongAdder();
	private final LongAdder errorCount = new LongAdder();
	private final LongAdder totalRequestTimeMs = new LongAdder();
	private final LongAdder totalLoopTimeMs = new LongAdder();
	private final AtomicLong maxRequestTimeMs = new AtomicLong();

	private final AtomicBoolean test = new AtomicBoolean(true);
	private final ConcurrentHashMap<String, Throwable> errors = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, LongAdder> errorsHistogram = new ConcurrentHashMap<>();
	private volatile AdaptiveHistogram histogram = null;

	protected void stop()
	{
		test.set(false);
	}

	protected void performance(final int threads, final int testTimeInSeconds, final Supplier<Observable<?>> action,
			final boolean displayHistogram, final boolean displayHistogramGraph, final int logSleepSeconds, final boolean stress)
			throws InterruptedException
	{
		requestCount.reset();
		loopCount.reset();
		errorCount.reset();
		totalRequestTimeMs.reset();
		totalLoopTimeMs.reset();
		maxRequestTimeMs.set(0);

		test.set(true);
		errors.clear();
		errorsHistogram.clear();

		histogram = new AdaptiveHistogram();

		final ExecutorService executorService = Executors.newFixedThreadPool(threads + 16);
		final Scheduler scheduler = Schedulers.from(executorService);

		IntStream.range(0, threads).forEach(v -> executorService.execute(() -> {
			do
			{
				final long start = System.currentTimeMillis();
				long sample;
				try
				{
					final Observable<?> observable = action.get() //
							.observeOn(scheduler) //
							.doOnCompleted(() -> registerExecution(start, maxRequestTimeMs, totalRequestTimeMs, requestCount,
									histogram)) //
							.doOnError(throwable -> {
								registerExecution(start, maxRequestTimeMs, totalRequestTimeMs, requestCount, histogram);
								errorCount.increment();
								errors.putIfAbsent(throwable.getMessage() == null ? "" : throwable.getMessage(), throwable);
								increment(errorsHistogram, throwable.getMessage() == null ? "" : throwable.getMessage());
							});

					if (stress)
					{
						observable.subscribe();
					}
					else
					{
						try
						{
							observable.toBlocking().lastOrDefault(null);
						}
						catch (final Exception e)
						{
							// already handled in doOnError
						}
					}

					sample = System.currentTimeMillis() - start;
				}
				catch (final Throwable t)
				{
					sample = System.currentTimeMillis() - start;
					errorCount.increment();
					errors.putIfAbsent(t.getMessage() == null ? "" : t.getMessage(), t);
					increment(errorsHistogram, t.getMessage() == null ? "" : t.getMessage());
				}
				max(maxRequestTimeMs, sample);
				totalLoopTimeMs.add(System.currentTimeMillis() - start);
				loopCount.increment();
			} while (test.get());
		}));

		final long start = System.currentTimeMillis();
		final long end = start + testTimeInSeconds * 1000;

		int i = 0;
		do
		{
			Thread.sleep(logSleepSeconds * 1000);
			final long testTimeMs = System.currentTimeMillis() - start;

			final double avgExecTimeMs = totalRequestTimeMs.doubleValue() / requestCount.doubleValue();


			str().print(""). //
					print("Sample results :"). //
					print(" - request rate  : " + (requestCount.doubleValue() * 1000. / testTimeMs) + " r/s"). //
					print(" - error rate    : " + (errorCount.doubleValue() * 1000. / testTimeMs) + " e/s"). //
					print(" - max exec time : " + maxRequestTimeMs.get() + " ms"). //
					print(" - avg exec time : " + avgExecTimeMs + " ms\n"). //
					flush();

			if (testTimeInSeconds == 0 && ++i > 16)
			{
				i = 0;
				printHistogram();
				histogram = new AdaptiveHistogram();
			}
		} while (test.get() && (testTimeInSeconds == 0 || System.currentTimeMillis() < end));

		test.set(false);
		executorService.shutdown();

		printErrors();

		printStats(testTimeInSeconds);

		if (displayHistogram)
		{
			printHistogram();
			if (displayHistogramGraph)
			{
				XYHistogramChart.display(histogram, "Request time (ms)");
			}
		}
	}

	private class Printer
	{
		private StringBuilder buf = new StringBuilder();

		public Printer print(final String s)
		{
			buf.append(s).append('\n');
			return this;
		}

		public void print(final String s, final Throwable value)
		{
			buf.append(s).append(" >> ").append(value).append('\n');
			ThroughputRunner.this.print(s, value);
		}

		@Override
		public String toString()
		{
			return buf.toString();
		}

		public Printer flush()
		{
			final String text = this.toString();
			if (text != null && text.length() > 0)
			{
				ThroughputRunner.this.lastLog = text;
				ThroughputRunner.this.print(text, null);
				buf.delete(0, buf.length());
			}
			return this;
		}
	}

	private Printer str()
	{
		return new Printer();
	}

	private void registerExecution(final long start, final AtomicLong maxRequestTimeMs, final LongAdder totalRequestTimeMs,
			final LongAdder requestCount, final AdaptiveHistogram histogram)
	{
		final long time = System.currentTimeMillis() - start;
		max(maxRequestTimeMs, time);
		totalRequestTimeMs.add(time);
		requestCount.increment();
		histogram.addValue(time);
	}

	protected void print(final String text, final Throwable t)
	{
		printer.accept(text, t);
	}

	private void increment(final ConcurrentHashMap<String, LongAdder> errorsHistogram, final String key)
	{
		LongAdder adder = errorsHistogram.get(key);
		if (adder == null)
		{
			final LongAdder tmp = new LongAdder();
			adder = errorsHistogram.putIfAbsent(key, tmp);
			if (adder == null)
			{
				adder = tmp;
			}
		}
		adder.increment();
	}

	private void max(final AtomicLong maxRequestTimeMs, long sample)
	{
		while (sample > maxRequestTimeMs.get())
		{
			sample = maxRequestTimeMs.getAndSet(sample);
		}
	}

	protected String printErrors()
	{
		final Printer p = str().print("\n"). //
				print("ERRORS " + errors.size() + " of " + errorCount.longValue());

		errors.entrySet().stream().forEach(e -> p.print(errorsHistogram.get(e.getKey()).longValue() + " times : " + e.getKey(),
				e.getValue()));

		final String txt = p.toString();
		p.flush();

		return txt;
	}

	protected String printStats(final int testTimeInSeconds)
	{
		final double avgExecTimeMs = totalRequestTimeMs.doubleValue() / requestCount.doubleValue();
		final Printer print = str();

		print.print("\n"). //
				print("REQUESTS: " + requestCount.longValue() + ", ERRORS: " + errorCount
				.longValue() + ", TOTAL_EXEC_TIME_MS: " + totalRequestTimeMs
				.longValue() + ", TOTAL_LOOP_TIME_MS: " + totalLoopTimeMs.longValue() + ", LOOPS: " + loopCount.longValue()). //
				print("  request rate  : " + (requestCount.doubleValue() / testTimeInSeconds) + " r/s"). //
				print("  error rate    : " + (errorCount.doubleValue() / testTimeInSeconds) + " e/s"). //
				print("  max exec time : " + maxRequestTimeMs.get() + " ms"). //
				print("  avg exec time : " + avgExecTimeMs + " ms"). //
				print("  avg loop time : " + (totalLoopTimeMs.doubleValue() / (requestCount.doubleValue() + errorCount
				.doubleValue())) + " ms"). //
				print("  thread rate   : " + (1000. / avgExecTimeMs) + " r/s"). //
				print("  effective req : " + (testTimeInSeconds * 1000. / requestCount.doubleValue()) + " ms\n");

		final String out = print.toString();
		print.flush();

		return out;
	}

	protected String printHistogram()
	{
		final AdaptiveHistogram h = histogram;
		final Printer print = str();

		if (h != null)
		{
			print.print("\n"). //
					print("Main percentiles (action execution time):"). //
					print("   5%: " + h.getValueForPercentile(5) + " ms"). //
					print("  25%: " + h.getValueForPercentile(25) + " ms"). //
					print("  50%: " + h.getValueForPercentile(50) + " ms"). //
					print("  75%: " + h.getValueForPercentile(75) + " ms"). //
					print("  80%: " + h.getValueForPercentile(80) + " ms"). //
					print("  85%: " + h.getValueForPercentile(85) + " ms"). //
					print("  90%: " + h.getValueForPercentile(90) + " ms"). //
					print("  95%: " + h.getValueForPercentile(95) + " ms"). //
					print("  99%: " + h.getValueForPercentile(99) + " ms"). //
					print("\n");
		}

		final String out = print.toString();
		print.flush();


		return out;
	}
}
