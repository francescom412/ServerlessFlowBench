package cmd.functionality_commands.output_parsing;

/**
 * Collector for Google CLI deployment urls
 */
public class UrlFinder {

	// string containing url
	private String result;

	// ignore SSL/TLS certificate validation in Open Whisk
	private boolean openWhiskIgnoreSSL;

	/**
	 * Default constructor
	 */
	public UrlFinder() {
		result = "";
		openWhiskIgnoreSSL = false;
	}

	/**
	 * One argument constructor
	 * @param openWhiskIgnoreSSL tells whether to ignore SSL/TLS certificate validation or not in Open Whisk
	 */
	public UrlFinder(boolean openWhiskIgnoreSSL) {
		result = "";
		this.openWhiskIgnoreSSL = openWhiskIgnoreSSL;
	}

	/**
	 * Searches for the URL inside the passed string
	 * @param string output line to perform search on
	 */
	public void findGoogleCloudFunctionsUrl(String string) {
		if (string.contains("url: ")) {
			// exclude un-needed information and blank spaces
			String url = string.substring(string.indexOf("url: "));
			// remove preamble
			result = url.replace("url: ", "");
		}
	}

	/**
	 * Searches for the URL inside the passed string
	 * @param string output line to perform search on
	 */
	public void findOpenWhiskUrl(String string) {
		if (string.contains("https")) {
			// url found
			if (openWhiskIgnoreSSL) {
				// ignore ssl certificate validation
				string = string.replace("https", "http");
			}
			result = string;
		}
	}

	/**
	 * Get final result
	 * @return string containing the url
	 */
	public String getResult() {
		return result;
	}
}
