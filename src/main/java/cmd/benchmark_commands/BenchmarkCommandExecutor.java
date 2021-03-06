package cmd.benchmark_commands;

import cmd.CommandExecutor;
import cmd.docker_daemon_utility.DockerException;
import cmd.docker_daemon_utility.DockerExecutor;
import cmd.StreamGobbler;
import cmd.benchmark_commands.output_parsing.BenchmarkCollector;
import cmd.benchmark_commands.output_parsing.BenchmarkStats;
import com.google.api.client.http.HttpStatusCodes;
import com.sun.istack.internal.NotNull;
import databases.influx.InfluxClient;
import databases.mysql.FunctionalityURL;
import databases.mysql.daos.CompositionsRepositoryDAO;
import databases.mysql.daos.FunctionsRepositoryDAO;
import jline.internal.Nullable;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Utility for benchmarks execution.
 */
public class BenchmarkCommandExecutor extends CommandExecutor {

	/**
	 * Time needed for instance recycling, value determined using
	 * https://www.usenix.org/conference/atc18/presentation/wang-liang
	 */
	private static final int COLD_START_SLEEP_INTERVAL_MS = 120 * 60 * 1000;

	/**
	 * Cold start evaluation default parameters
	 */
	private static final Integer DEFAULT_IGNORED_COLD_START_VALUES = 5;
	private static final Integer DEFAULT_WARM_START_AVG_WIDTH = 5;

	/**
	 * Maximum time needed for HTTP API execution default value
	 */
	private static final int TIMEOUT_REQUEST_INTERVAL_MS = 30 * 60 * 1000;

	/**
	 * Semaphores
	 */
	private final Semaphore coldStartSem;
	private final Semaphore benchmarkSem;

	/**
	 * Concurrency info
	 */
	private final int minConcurrencyLevel;


	/**
	 * Constructor, initializes maximum concurrency levels with specified values
	 * @param maxColdStartConcurrency maximum concurrent cold start test amount
	 * @param maxLoadBenchmarkConcurrency maximum concurrent load test amount
	 */
	public BenchmarkCommandExecutor(int maxColdStartConcurrency, int maxLoadBenchmarkConcurrency) {
		coldStartSem = new Semaphore(maxColdStartConcurrency, true);
		benchmarkSem = new Semaphore(maxLoadBenchmarkConcurrency, true);
		minConcurrencyLevel = Math.min(maxColdStartConcurrency, maxLoadBenchmarkConcurrency);
	}

	/**
	 * Constructor, initializes maximum concurrency levels with specified value independent from the type of test
	 * @param maxConcurrency maximum concurrent load test amount
	 */
	public BenchmarkCommandExecutor(int maxConcurrency) {
		Semaphore uniqueSem = new Semaphore(maxConcurrency, true);
		coldStartSem = uniqueSem;
		benchmarkSem = uniqueSem;
		minConcurrencyLevel = maxConcurrency;
	}

	/**
	 * Perform a load benchmark through wrk2
	 * @param url url to test
	 * @param concurrency number of HTTP open connections
	 * @param threads number of threads
	 * @param seconds test duration
	 * @param requestsPerSecond number of requests per second
	 * @return benchmark result as BenchmarkStats
	 */
	private static BenchmarkStats performBenchmark(String url, Integer concurrency, Integer threads, Integer seconds,
										Integer requestsPerSecond) {

		try {
			BenchmarkCollector collector = new BenchmarkCollector();
			String cmd = BenchmarkCommandUtility.buildBenchmarkCommand(url, concurrency, threads, seconds,
					requestsPerSecond);

			Process process = buildCommand(cmd).start();

			StreamGobbler outputGobbler = new StreamGobbler(process.getInputStream(), collector::parseAndCollect);
			ExecutorService executorService = Executors.newSingleThreadExecutor();
			executorService.submit(outputGobbler);

			if (process.waitFor() != 0) {
				System.err.println("Could not perform benchmark!");
				return null;
			}

			process.destroy();
			executorService.shutdown();

			return collector.getResult();

		} catch (InterruptedException | IOException e) {
			System.err.println("Could not perform benchmark: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Evaluate latency gap between cold and warm start
	 * @param targetUrl url to test
	 * @param timeoutRequestMs maximum time in milliseconds before request timeout occurs
	 * @param ignoredValues number of request to ignore due to cold start management inconsistency
	 * @param avgAmount number of warm start to perform to evaluate the average warm latency
	 * @return gap in milliseconds
	 */
	private static double measureColdStartCost(String targetUrl, Integer timeoutRequestMs, int ignoredValues,
											   int avgAmount) {

		long coldStartLatency;
		long warmLatency;
		// measure cold start latency
		do {
			coldStartLatency = measureHttpLatency(targetUrl, timeoutRequestMs);
		} while (coldStartLatency == -2);
		if (coldStartLatency < 0) {
			return -1;
		}
		ArrayList<Long> latencies = new ArrayList<>();
		// measure average warm start latency excluding first n requests to be sure of cold start to not occur again
		for (int i = 0; i < avgAmount; i++) {
			do {
				warmLatency = measureHttpLatency(targetUrl, timeoutRequestMs);
			} while (warmLatency < 0);

			if (i >= ignoredValues) {
				latencies.add(warmLatency);
			}
		}
		// evaluate average
		double avgWarmLatency = latencies.stream().mapToLong(a -> a).average().orElse(-1);
		if (avgWarmLatency < 0) {
			return -1;
		}
		double result = coldStartLatency - avgWarmLatency;
		// if result is negative cold start didn't occur so return value is 0
		return result < 0 ? 0 : result;
	}

	/**
	 * Measure a single http request latency
	 * @param targetUrl url to test
	 * @param timeoutRequestMs maximum time in milliseconds before request timeout occurs
	 * @return latency in milliseconds
	 */
	private static long measureHttpLatency(String targetUrl, Integer timeoutRequestMs) {

		HttpURLConnection connection = null;
		InputStream inputStream;
		BufferedReader reader;

		try {

			long startTime = System.currentTimeMillis();

			// create connection
			URL url = new URL(targetUrl);
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("GET");
			connection.setUseCaches(false);
			connection.setDoOutput(true);
			// set timeout interval
			connection.setConnectTimeout(timeoutRequestMs);
			connection.setReadTimeout(timeoutRequestMs);

			inputStream = connection.getInputStream();
			reader = new BufferedReader(new InputStreamReader(inputStream));
			// noinspection StatementWithEmptyBody
			while (reader.readLine() != null) {}
			long latency = System.currentTimeMillis() - startTime;
			inputStream.close();
			reader.close();
			return latency;


		} catch (IOException e) {
			if (e.getMessage() != null &&
					(e.getMessage().contains(" " + HttpStatusCodes.STATUS_CODE_SERVICE_UNAVAILABLE + " ") ||
					e.getMessage().contains(" " + HttpStatusCodes.STATUS_CODE_BAD_GATEWAY + " "))) {
				// needs to retry
				return -2;
			}
			System.err.println("Could not perform HTTP request: " + e.getMessage());
			return -1;
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	/**
	 * Collects url of both serverless functions and compositions
	 * @return list of FunctionalityURL for both serverless functions and compositions
	 */
	private static List<FunctionalityURL> extractUrls() {
		List<FunctionalityURL> total = new ArrayList<>();

		List<FunctionalityURL> functions = FunctionsRepositoryDAO.getUrls();
		if (functions == null || functions.isEmpty()) {
			System.err.println("WARNING: No function to test");
		} else {
			total.addAll(functions);
		}
		List<FunctionalityURL> machines = CompositionsRepositoryDAO.getUrls();
		if (machines == null || machines.isEmpty()) {
			System.err.println("WARNING: No composition to test");
		} else {
			total.addAll(machines);
		}

		return total;
	}

	/**
	 * Deprecated, performs cold start benchmarks
	 * @param iterations number of test
	 */
	@Deprecated
	public static void performColdStartBenchmark(int iterations) {
		System.out.println("\n" + "\u001B[33m" +
				"Starting cold start benchmarks...\nFrom this moment on please make sure no one else is invoking " +
				"your functions.\n" + "Estimated time: approximately " +
				(((COLD_START_SLEEP_INTERVAL_MS/1000)*iterations)/60)/60 + " hours" + "\u001B[0m" + "\n");

		List<FunctionalityURL> total = extractUrls();
		if (total.isEmpty()) {
			System.err.println("Could not perform benchmarks");
			return;
		}

		ArrayList<Thread> threads = new ArrayList<>();
		ColdTestRunner runner;
		Thread t;

		for (FunctionalityURL url : total) {
			runner = new ColdTestRunner(url, iterations, COLD_START_SLEEP_INTERVAL_MS);
			t = new Thread(runner);
			threads.add(t);
			t.start();
		}
		for (Thread thread : threads) {
			try {
				thread.join();
			} catch (InterruptedException ignored) {}
		}

		System.out.println("\u001B[32m" + "Cold start benchmark completed!" + "\u001B[0m");
	}

	/**
	 * Deprecated, performs load benchmark
	 * @param concurrency number of HTTP open connections
	 * @param threadNum number of threads
	 * @param seconds test duration
	 * @param requestsPerSecond number of requests per second
	 */
	@Deprecated
	public static void performLoadTest(Integer concurrency, Integer threadNum, Integer seconds,
									   Integer requestsPerSecond) {

		System.out.println("\n" + "\u001B[33m" +
				"Starting load benchmarks..." +
				"\u001B[0m" + "\n");

		List<FunctionalityURL> total = extractUrls();
		if (total.isEmpty()) {
			System.err.println("Could not perform benchmarks");
			return;
		}

		ArrayList<Thread> threads = new ArrayList<>();
		LoadTestRunner runner;
		Thread t;

		for (FunctionalityURL url : total) {
			runner = new LoadTestRunner(url, concurrency, threadNum, seconds, requestsPerSecond);
			t = new Thread(runner);
			threads.add(t);
			t.start();
		}
		for (Thread thread : threads) {
			try {
				thread.join();
			} catch (InterruptedException ignored) {
			}
		}

		System.out.println("\u001B[32m" + "Load benchmark completed!" + "\u001B[0m");
	}

	/**
	 * Performs multiple cold start and load benchmarks
	 * @param concurrency number of HTTP open connections in load test
	 * @param threadNum number of threads in load test
	 * @param seconds load test duration
	 * @param requestsPerSecond requests per second in load test
	 * @param sleepIntervalMs interval in milliseconds for functions VM deletion and cold start perform if function is
	 *                        invoked, if null default value will be used
	 * @param timeoutRequestMs maximum time in milliseconds before request timeout occurs in cold start measurement,
	 *                         if null default value will be used
	 * @param iterations number of iterations, if null the test will run indefinitely (continuous monitoring)
	 * @param ignoredColdStartValues number of request to ignore due to cold start management inconsistency
	 * @param warmStartAverageWidth number of warm start to perform to evaluate the average warm latency
	 */
	public void performBenchmarks(Integer concurrency, Integer threadNum, Integer seconds, Integer requestsPerSecond,
								  @Nullable Integer sleepIntervalMs, @Nullable Integer timeoutRequestMs,
								  @Nullable Integer iterations,
								  @Nullable Integer ignoredColdStartValues, @Nullable Integer warmStartAverageWidth) {

		try {
			DockerExecutor.checkDocker();
		} catch (DockerException e) {
			System.err.println("Could not perform benchmarks: " + e.getMessage());
			return;
		}

		if (iterations!= null && iterations <= 0) {
			System.err.println("Could not perform benchmarks: iterations number must be greater than 0");
			return;
		}

		if (sleepIntervalMs != null && sleepIntervalMs <= 0) {
			System.err.println("Could not perform benchmarks: sleep interval in milliseconds must be greater than 0");
			return;
		} else if (sleepIntervalMs == null) {
			sleepIntervalMs = COLD_START_SLEEP_INTERVAL_MS;
		}

		if (timeoutRequestMs != null && timeoutRequestMs <= 0) {
			System.err.println("Could not perform benchmarks: request timeout in milliseconds must be greater than 0");
			return;
		} else if (timeoutRequestMs == null) {
			timeoutRequestMs = TIMEOUT_REQUEST_INTERVAL_MS;
		}

		if (ignoredColdStartValues != null && ignoredColdStartValues <= 0) {
			System.err.println("Could not perform benchmarks: ignored cold start values must be greater than 0");
			return;
		} else if (ignoredColdStartValues == null) {
			ignoredColdStartValues = DEFAULT_IGNORED_COLD_START_VALUES;
		}

		if (warmStartAverageWidth != null && warmStartAverageWidth <= 0) {
			System.err.println("Could not perform benchmarks: warm start latencies amount must be greater than 0");
			return;
		} else if (warmStartAverageWidth == null) {
			warmStartAverageWidth = DEFAULT_WARM_START_AVG_WIDTH;
		}

		if (ignoredColdStartValues >= warmStartAverageWidth) {
			System.err.println("Could not perform benchmarks: warm start latencies amount must be greater than ignored " +
					"cold start values");
			return;
		}

		List<FunctionalityURL> total = extractUrls();
		if (total.isEmpty()) {
			System.err.println("Could not perform benchmarks: no functionality to test found");
			return;
		}

		System.out.print("\n" + "\u001B[33m" +
				"Starting benchmarks...\nFrom this moment on please make sure no one else is invoking " +
				"your functions.\n");
		if (iterations != null) {
			double durationSeconds = ((sleepIntervalMs/1000.0 + (seconds * total.size() / (double)minConcurrencyLevel))
					* iterations);
			int durationHours = (int)Math.floor((durationSeconds/60)/60);
			int durationMinutes = (int)Math.floor(durationSeconds/60 - durationHours*60);
			System.out.print("Estimated time: approximately " + durationHours + " hour(s) and " +
					durationMinutes + " minute(s)");
		}
		System.out.println("\u001B[0m" + "\n");

		ArrayList<Thread> threads = new ArrayList<>();
		BenchmarkRunner runner;
		Thread t;

		for (FunctionalityURL url : total) {
			runner = new BenchmarkRunner(url, concurrency, threadNum, seconds, requestsPerSecond,
					sleepIntervalMs, timeoutRequestMs, iterations, ignoredColdStartValues, warmStartAverageWidth,
					coldStartSem, benchmarkSem);
			t = new Thread(runner);
			threads.add(t);
			t.start();
		}

		System.out.println("Benchmark started in background!");

		for (Thread thread : threads) {
			try {
				thread.join();
			} catch (InterruptedException ignored) {
			}
		}

		System.out.println("\u001B[32m" + "Benchmark completed!" + "\u001B[0m");
	}

	/**
	 * Deprecated, runnable inner class for multiple load benchmarks performing
	 */
	@Deprecated
	private static class LoadTestRunner implements Runnable {

		private final FunctionalityURL function;
		private final Integer concurrency;
		private final Integer threads;
		private final Integer seconds;
		private final Integer requestsPerSecond;


		/**
		 * Default constructor
		 * @param function function url
		 * @param concurrency number of HTTP open connections
		 * @param threads number of threads
		 * @param seconds test duration
		 * @param requestsPerSecond number of requests per second
		 */
		public LoadTestRunner(FunctionalityURL function, Integer concurrency, Integer threads, Integer seconds,
							  Integer requestsPerSecond) {
			this.function = function;
			this.concurrency = concurrency;
			this.threads = threads;
			this.seconds = seconds;
			this.requestsPerSecond = requestsPerSecond;
		}

		@Override
		public void run() {

			BenchmarkStats google;
			BenchmarkStats amazon;

			if (function.getGoogleUrl() == null) {
				google = null;
			} else {
				google = performBenchmark(function.getGoogleUrl(), concurrency, threads, seconds, requestsPerSecond);
			}

			if (function.getAmazonUrl() == null) {
				amazon = null;
			} else {
				amazon = performBenchmark(function.getAmazonUrl(), concurrency, threads, seconds, requestsPerSecond);
			}

			if (google != null) {
				System.out.println(function.getName() + " avg latency google = " + google.getAvgLatency());
				if (InfluxClient.insertLoadPoints(function.getName(), "google", google,
						System.currentTimeMillis())) {
					System.out.println("\u001B[32m" + "Persisted google benchmark for: " + function.getName() +
							"\u001B[0m");
				}
			}
			if (amazon != null) {
				System.out.println(function.getName() + " avg latency amazon = " + amazon.getAvgLatency());
				if (InfluxClient.insertLoadPoints(function.getName(), "amazon", amazon,
						System.currentTimeMillis())) {
					System.out.println("\u001B[32m" + "Persisted amazon benchmark for: " + function.getName() +
							"\u001B[0m");
				}
			}

		}
	}

	/**
	 * Deprecated, runnable inner class for multiple cold start benchmarks performing
	 */
	@Deprecated
	private static class ColdTestRunner implements Runnable {

		private final FunctionalityURL function;
		private final Integer iterations;
		private final Integer sleepMs;


		/**
		 * Default constructor
		 * @param function function url
		 * @param iterations number of iterations
		 * @param sleepMs time between two cold starts
		 */
		public ColdTestRunner(FunctionalityURL function, Integer iterations, Integer sleepMs) {
			this.function = function;
			this.iterations = iterations;
			this.sleepMs = sleepMs;
		}

		@Override
		public void run() {

			long googleLatency;
			long amazonLatency;

			for (int i = 0; i < iterations; i++) {
				try {
					// time to let provider deallocate resources for function execution
					Thread.sleep(sleepMs);
					googleLatency = measureHttpLatency(function.getGoogleUrl(), TIMEOUT_REQUEST_INTERVAL_MS);
					amazonLatency = measureHttpLatency(function.getAmazonUrl(), TIMEOUT_REQUEST_INTERVAL_MS);
					if (googleLatency >= 0) {
						// influx persist
						if (InfluxClient.insertColdPoint(function.getName(), "google", googleLatency,
								System.currentTimeMillis())) {
							System.out.println("\u001B[32m" + "Persisted google cold start benchmark for: " +
									function.getName() + "\u001B[0m");
						}
					}
					if (amazonLatency >= 0) {
						// influx persist
						if (InfluxClient.insertColdPoint(function.getName(), "amazon", amazonLatency,
								System.currentTimeMillis())) {
							System.out.println("\u001B[32m" + "Persisted amazon cold start benchmark for: " +
									function.getName() + "\u001B[0m");
						}
					}
				} catch (InterruptedException ignored) {
					return;
				}
			}

		}
	}

	/**
	 * Runnable inner class for multiple cold start and load benchmarks performing
	 */
	private static class BenchmarkRunner implements Runnable {

		private final FunctionalityURL function;
		private final Integer concurrency;
		private final Integer threads;
		private final Integer seconds;
		private final Integer requestsPerSecond;
		private final Integer timeoutRequestMs;
		private final Integer sleepMs;
		private Integer iterations;
		private final Integer ignoredColdStartValues;
		private final Integer warmStartAverageWidth;

		private final Semaphore coldStartSem;
		private final Semaphore benchmarkSem;


		/**
		 * Default constructor
		 * @param function function url
		 * @param concurrency number of HTTP open connections in load test
		 * @param threads number of threads in load test
		 * @param seconds load test duration
		 * @param requestsPerSecond requests per second in load test
		 * @param sleepMs time between two cold start benchmark
		 * @param timeoutRequestMs maximum time in milliseconds before request timeout occurs in cold start measurement
		 * @param iterations number of iterations, can be null and the test will run indefinitely
		 * @param ignoredColdStartValues number of request to ignore due to cold start management inconsistency
		 * @param warmStartAverageWidth number of warm start to perform to evaluate the average warm latency
		 */
		public BenchmarkRunner(@NotNull FunctionalityURL function, @NotNull Integer concurrency,
							   @NotNull Integer threads, @NotNull Integer seconds, @NotNull Integer requestsPerSecond,
							   @NotNull Integer sleepMs, @NotNull Integer timeoutRequestMs,
							   @Nullable Integer iterations, @NotNull Integer ignoredColdStartValues,
							   @NotNull Integer warmStartAverageWidth,
							   @NotNull Semaphore coldStartSem, @NotNull Semaphore benchmarkSem) {
			this.function = function;
			this.concurrency = concurrency;
			this.threads = threads;
			this.seconds = seconds;
			this.requestsPerSecond = requestsPerSecond;
			this.sleepMs = sleepMs;
			this.timeoutRequestMs = timeoutRequestMs;

			if (iterations == null) {
				this.iterations = -1;
			} else {
				this.iterations = iterations;
			}

			this.ignoredColdStartValues = ignoredColdStartValues;
			this.warmStartAverageWidth = warmStartAverageWidth;

			this.coldStartSem = coldStartSem;
			this.benchmarkSem = benchmarkSem;
		}

		/**
		 * Sleep to let container deallocating occur
		 * @throws InterruptedException thread interruption related exception
		 */
		public void performColdStartWait() throws InterruptedException {
			Thread.sleep(sleepMs);
		}

		/**
		 * Every sleepMs milliseconds a cold start benchmark is performed and next a load test.
		 */
		@Override
		public void run() {

			boolean google = (function.getGoogleUrl() != null);
			boolean amazon = (function.getAmazonUrl() != null);
			boolean openWhisk = (function.getOpenWhiskUrl() != null);

			if (!google && !amazon && !openWhisk) {
				System.out.println("No url to test for '" + function.getName() + "'");
				return;
			}

			double googleLatency;
			double amazonLatency;
			double openWhiskLatency;

			BenchmarkStats googleStats;
			BenchmarkStats amazonStats;
			BenchmarkStats openWhiskStats;

			int attempts;

			while (iterations != 0) {
				// time to let provider deallocate resources for function execution
				try {
					performColdStartWait();
				} catch (InterruptedException ignored) {
					return;
				}

				if (google) {

					// cold start test
					try {
						coldStartSem.acquire();
					} catch (InterruptedException ignored) {
						return;
					}
					while ((googleLatency = measureColdStartCost(function.getGoogleUrl(), timeoutRequestMs,
							ignoredColdStartValues, warmStartAverageWidth)) < 0) {
						coldStartSem.release();
						// needs retry because service was un-available
						try {
							System.err.println(function.getName() + " service is un-available, performing new trial");
							performColdStartWait();
							coldStartSem.acquire();
						} catch (InterruptedException ignored) {
							return;
						}
					}
					coldStartSem.release();

					// influx persist
					if (InfluxClient.insertColdPoint(function.getName(), "google", googleLatency,
							System.currentTimeMillis())) {
						System.out.println("\u001B[32m" + "Persisted Google cold start benchmark for: " +
								function.getName() + "\u001B[0m");
					} else {
						System.err.println("Failed persisting Google cold start latency for "
								+ function.getName() + ": parameters or connection error");
					}

					// load test
					try {
						benchmarkSem.acquire();
					} catch (InterruptedException ignored) {
						return;
					}
					attempts = 0;
					do {
						attempts++;
						if (attempts > 1) {
							System.err.println("WARNING: repeating Google load test for '" + function.getName() + "'");
						}
						googleStats = performBenchmark(function.getGoogleUrl(), concurrency, threads, seconds,
								requestsPerSecond);
					} while (googleStats == null || googleStats.getAvgLatency() == null);
					benchmarkSem.release();

					System.out.println(function.getName() + " avg latency Google = " + googleStats.getAvgLatency());
					// influx persist
					if (InfluxClient.insertLoadPoints(function.getName(), "google", googleStats,
							System.currentTimeMillis())) {
						System.out.println("\u001B[32m" + "Persisted Google benchmark for: " + function.getName() +
								"\u001B[0m");
					} else {
						System.err.println("Failed persisting Google benchmarks "
								+ function.getName() + ": parameters or connection error");
					}
				}

				if (amazon) {

					// cold start test
					try {
						coldStartSem.acquire();
					} catch (InterruptedException ignored) {
						return;
					}
					while ((amazonLatency = measureColdStartCost(function.getAmazonUrl(), timeoutRequestMs,
							ignoredColdStartValues, warmStartAverageWidth)) < 0){
						coldStartSem.release();
						// needs retry because service was un-available
						try {
							System.err.println(function.getName() + " service is un-available, performing new trial");
							performColdStartWait();
							coldStartSem.acquire();
						} catch (InterruptedException ignored) {
							return;
						}
					}
					coldStartSem.release();

					// influx persist
					if (InfluxClient.insertColdPoint(function.getName(), "amazon", amazonLatency,
							System.currentTimeMillis())) {
						System.out.println("\u001B[32m" + "Persisted Amazon cold start benchmark for: " +
								function.getName() + "\u001B[0m");
					} else {
						System.err.println("Failed persisting Amazon cold start latency for "
								+ function.getName() + ": parameters or connection error");
					}

					// load test
					try {
						benchmarkSem.acquire();
					} catch (InterruptedException ignored) {
						return;
					}
					attempts = 0;
					do {
						attempts++;
						if (attempts > 1) {
							System.err.println("WARNING: repeating Amazon load test for '" + function.getName() + "'");
						}
						amazonStats = performBenchmark(function.getAmazonUrl(), concurrency, threads, seconds,
								requestsPerSecond);
					} while (amazonStats == null || amazonStats.getAvgLatency() == null);
					benchmarkSem.release();

					System.out.println(function.getName() + " avg latency Amazon = " + amazonStats.getAvgLatency());
					if (InfluxClient.insertLoadPoints(function.getName(), "amazon", amazonStats,
							System.currentTimeMillis())) {
						System.out.println("\u001B[32m" + "Persisted Amazon benchmark for: " + function.getName() +
								"\u001B[0m");
					} else {
						System.err.println("Failed persisting Amazon benchmarks "
								+ function.getName() + ": parameters or connection error");
					}
				}

				if (openWhisk) {
					// cold start test
					try {
						coldStartSem.acquire();
					} catch (InterruptedException ignored) {
						return;
					}
					while ((openWhiskLatency = measureColdStartCost(function.getOpenWhiskUrl(),
							timeoutRequestMs, ignoredColdStartValues, warmStartAverageWidth)) < 0) {
						coldStartSem.release();
						// needs retry because service was un-available
						try {
							System.err.println(function.getName() + " service is un-available, performing new trial");
							performColdStartWait();
							coldStartSem.acquire();
						} catch (InterruptedException ignored) {
							return;
						}
					}
					coldStartSem.release();

					// influx persist
					if (InfluxClient.insertColdPoint(function.getName(), "openwhisk", openWhiskLatency,
							System.currentTimeMillis())) {
						System.out.println("\u001B[32m" + "Persisted OpenWhisk cold start benchmark for: " +
								function.getName() + "\u001B[0m");
					} else {
						System.err.println("Failed persisting OpenWhisk cold start latency for "
								+ function.getName() + ": parameters or connection error");
					}

					// load test
					try {
						benchmarkSem.acquire();
					} catch (InterruptedException ignored) {
						return;
					}
					attempts = 0;
					do {
						attempts++;
						if (attempts > 1) {
							System.err.println("WARNING: repeating OpenWhisk load test for '" + function.getName() +
									"'");
						}
						openWhiskStats = performBenchmark(function.getOpenWhiskUrl(), concurrency, threads, seconds,
								requestsPerSecond);
					} while (openWhiskStats == null || openWhiskStats.getAvgLatency() == null);
					benchmarkSem.release();

					System.out.println(function.getName() + " avg latency OpenWhisk = " +
							openWhiskStats.getAvgLatency());
					// influx persist
					if (InfluxClient.insertLoadPoints(function.getName(), "openwhisk", openWhiskStats,
							System.currentTimeMillis())) {
						System.out.println("\u001B[32m" + "Persisted OpenWhisk benchmark for: " + function.getName() +
								"\u001B[0m");
					} else {
						System.err.println("Failed persisting OpenWhisk benchmarks "
								+ function.getName() + ": parameters or connection error");
					}
				}

				iterations--;
			}
		}
	}
}
