package utility;

import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;

public class PropertiesManager {

	private final static String PROPERTIES_PATH = "config.properties";
	private static PropertiesManager singletonInstance = null;
	private Properties properties = null;

	/* PROPERTY KEYS */

	public static final String MYSQL_IP = "mysql_ip";
	public static final String MYSQL_PORT = "mysql_port";
	public static final String MYSQL_USR = "mysql_user";
	public static final String MYSQL_PASS = "mysql_password";
	public static final String MYSQL_DB = "mysql_dbname";

	public static final String INFLUX_IP = "influx_ip";
	public static final String INFLUX_PORT = "influx_port";
	public static final String INFLUX_USR = "influx_user";
	public static final String INFLUX_PASS = "influx_password";
	public static final String INFLUX_DB = "influx_dbname";

	public static final String GOOGLE_CONTAINER = "google_cloud_cli_container_name";
	public static final String GOOGLE_STAGE_BUCKET = "google_cloud_stage_bucket";
	public static final String GOOGLE_AUTH_JSON = "gcloud_auth_json_path";

	public static final String AWS_AUTH_CONFIG = "aws_auth_folder_path";
	public static final String AWS_LAMBDA_EXEC_ROLE = "aws_lambda_execution_role";
	public static final String AWS_STEP_FUNCTIONS_EXEC_ROLE = "aws_stepfunctions_execution_role";

	public static final String AWS_HANDLER_PATH = "aws_handler_function_path";

	public static PropertiesManager getInstance() {

		if (singletonInstance == null) {
			singletonInstance = new PropertiesManager();
		}
		return singletonInstance;
	}

	private PropertiesManager() {
	}

	public String getProperty(String propertyKey) {
		try {
			if (properties == null) {
				properties = new Properties();
				properties.load(new FileReader(PROPERTIES_PATH));
			}
			return properties.getProperty(propertyKey);
		} catch (IOException ignored) {
			System.err.println("Error in configuration file! Please check " + PROPERTIES_PATH);
			return "";
		}
	}
}
