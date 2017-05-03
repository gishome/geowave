package mil.nga.giat.geowave.format.twitter.stream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import com.google.common.collect.Lists;
import com.twitter.hbc.ClientBuilder;
import com.twitter.hbc.core.Constants;
import com.twitter.hbc.core.endpoint.Location;
import com.twitter.hbc.core.endpoint.StatusesFilterEndpoint;
import com.twitter.hbc.core.processor.StringDelimitedProcessor;
import com.twitter.hbc.httpclient.BasicClient;
import com.twitter.hbc.httpclient.auth.Authentication;
import com.twitter.hbc.httpclient.auth.OAuth1;
import com.twitter.hbc.twitter4j.Twitter4jStatusClient;

import twitter4j.StatusListener;

public class TwitterStreamArchiver
{
	private long pollingFrequency = 5000L; // ms
	private String consumerKey;
	private String consumerSecret;
	private String accessToken;
	private String accessSecret;
	private String archivePath;
	private boolean init = false;

	public TwitterStreamArchiver() {
		
	}
	
	public void init(Properties twitterProps) throws IOException {
		if (twitterProps == null) {
			throw new IOException("Twitter configuration properties required!");
		}
		
		consumerKey = twitterProps.getProperty("twitter.consumer.key");
		if (consumerKey == null) {
			throw new IOException("Twitter Consumer Key required!");
		}
		
		consumerSecret = twitterProps.getProperty("twitter.consumer.secret");
		if (consumerSecret == null) {
			throw new IOException("Twitter Consumer Secret required!");
		}

		accessToken = twitterProps.getProperty("twitter.access.token");
		if (accessToken == null) {
			throw new IOException("Twitter Access Token required!");
		}

		accessSecret = twitterProps.getProperty("twitter.access.secret");
		if (accessSecret == null) {
			throw new IOException("Twitter Access Secret required!");
		}
		
		archivePath = twitterProps.getProperty("twitter.archivepath");
		if (archivePath == null) {
			throw new IOException("Twitter Archive Path required!");
		}
		
		String pollingFrequencyStr = twitterProps.getProperty("twitter.pollingfrequencyMillis", "5000");
		if (pollingFrequencyStr != null) {
			pollingFrequency = Long.parseLong(pollingFrequencyStr);
		}
	}

	public void run()
			throws InterruptedException, IOException {
		if (!init) {
			throw new IOException("TwitterStreamArchiver not initialized!");
		}
		
		BlockingQueue<String> queue = new LinkedBlockingQueue<String>(
				10000);
		
		TwitterArchiveWriter archiveWriter = new TwitterArchiveFileWriter(archivePath);

		StatusListener statusListener = new TwitterLocationListener(archiveWriter);

		// This should be configurable?
		Location wholeWorld = new Location(
				new Location.Coordinate(
						-180.0,
						-90.0),
				new Location.Coordinate(
						180.0,
						90.0));
		ArrayList<Location> locations = new ArrayList<>();
		locations.add(
				wholeWorld);

		// Use the filter endpoint
		StatusesFilterEndpoint endpoint = (new StatusesFilterEndpoint()).locations(
				locations);

		System.out.println(
				endpoint.getHttpMethod());

		Authentication auth = new OAuth1(
				consumerKey,
				consumerSecret,
				accessToken,
				accessSecret);

		// Create a new BasicClient. By default gzip is enabled.
		BasicClient client = new ClientBuilder()
				.hosts(
						Constants.STREAM_HOST)
				.endpoint(
						endpoint)
				.authentication(
						auth)
				.processor(
						new StringDelimitedProcessor(
								queue))
				.build();

		// Create an executor service which will spawn threads to do the actual
		// work of parsing the incoming messages and
		// calling the listeners on each message
		int numProcessingThreads = 4;
		ExecutorService service = Executors.newFixedThreadPool(
				numProcessingThreads);

		// Wrap our BasicClient with the twitter4j client
		Twitter4jStatusClient t4jClient = new Twitter4jStatusClient(
				client,
				queue,
				Lists.newArrayList(
						statusListener),
				service);

		// Establish a connection
		t4jClient.connect();

		while (!t4jClient.isDone()) {
			for (int threads = 0; threads < numProcessingThreads; threads++) {
				// This must be called once per processing thread
				t4jClient.process();
			}

			Thread.sleep(
					pollingFrequency);
		}

		client.stop();
	}
}