package pl.softwaremill.common.test.web.jboss;

import org.apache.commons.io.FileUtils;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeSuite;
import pl.softwaremill.common.test.web.selenium.ServerProperties;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

/**
 * @author maciek
 * @author Pawel Wrzeszcz
 * @author Jaroslaw Kijanowski
 */
public abstract class AbstractJBossRunner {

	private String serverHome;
	private String configuration;
	private boolean running;
	private int portset;
	private String additionalSystemProperties;
    private int asVersion;
    private boolean secured;
    private String username;
    private String password;

	Process jbossProcess;

	protected final static SysoutLog log = new SysoutLog();

	private static final String STARTED_LOG_MESSAGE = "Started in";

	private boolean deploymentComplete = false;

	private static final int MILLISECONDS_IN_MINUTE = 60 * 1000;

	protected abstract ServerProperties getServerProperties();

	protected abstract Deployment[] getDeployments();

	private Process tailProcess;

	@BeforeSuite
	public void start() throws Exception {
		loadProperties();
		scheduleTimeout();
		undeploy(); // Clean old deployments
		startServerIfNeeded();
		deploy();
	}

	@AfterSuite(alwaysRun = true)
	public void shutdown() throws Exception {
		undeploy();
		if (!running) {
			log.info("Stopping JBoss server");
			shutdownServer();
			log.info("JBoss Server stopped");
		}
		publishLog();
	}

	private void loadProperties() {
		ServerProperties serverProperties = getServerProperties();

		this.serverHome = serverProperties.getServerHome();
		this.configuration = serverProperties.getConfiguration();
		this.running = serverProperties.isRunning();
		this.portset = serverProperties.getPortset();
		this.additionalSystemProperties = serverProperties.getAdditionalSystemProperties();
        this.asVersion = serverProperties.getAsVersion();
        this.secured = serverProperties.isSecured();
        this.username = serverProperties.getUsername();
        this.password = serverProperties.getPassword();
	}

	private void startServerIfNeeded() throws Exception {
		if (!running) {
			log.info("Starting JBoss server");
			startServer();
			log.info("JBoss started");
		}
	}

	protected void startServer() throws Exception {
		jbossProcess = Runtime.getRuntime().exec(new String[]{serverHome + createRunScript(), "-c", configuration,
				createPortSetCommand(), additionalSystemProperties});

		waitFor(jbossProcess, STARTED_LOG_MESSAGE);

		tailProcess = jbossProcess;
	}

	private String createPortSetCommand() {
		if (portset <= 0) {
			return "";
		}

		return "-Djboss.service.binding.set=ports-0" + portset;
	}

	private String createRunScript() {
		if (winSystem()) {
			return "/bin/run.bat";
		}
		return "/bin/run.sh";
	}

	private boolean winSystem() {
		String osName = System.getProperty("os.name");
		return osName != null && osName.indexOf("Windows") != -1;
	}

	private void waitFor(Process process, String message) {
		log.info("Waiting for message: [" + message + "]");
		Scanner scanner = new Scanner(process.getInputStream()).useDelimiter(message);
		scanner.next();
	}

	private Process getTailProcess() throws IOException {
		if (tailProcess == null) {
			// this happens when jboss was already started
			tailProcess = Runtime.getRuntime().exec(
					new String[]{"tail", "-f", getServerLogPath()});
		}

		return tailProcess;
	}

	protected String getServerLogPath() {
		return serverHome + "/server/" + configuration + "/log/server.log";
	}

	protected void shutdownServer() throws IOException, InterruptedException {

        String[] paramString = new String[1];
        List<String> paramList = new ArrayList<String>();

        // bin/shutdown.{sh, bat}
        paramList.add(getServerProperties().getServerHome() + createShutdownScript());

        if(asVersion == 5){
            // JBoss5
            paramList.add("-s localhost:1" + getServerProperties().getPortset() + "99");
        } else {
            // JBoss6 and above
            paramList.add("--host=localhost");
            paramList.add("--port=1" + getServerProperties().getPortset() + "90");
        }

        // shutdown
        paramList.add("-S");

        if(secured){
            paramList.add("-u " + username);
            paramList.add("-p " + password);
        }

		final Process shutdownProcess = Runtime.getRuntime().exec(paramList.toArray(new String[0]));
        
		if (winSystem()) {
			PrintWriter writer = new PrintWriter(shutdownProcess.getOutputStream());
			try {
				//On windows, user needs to press any key on the console
				//for the process to exit. Wait a bit for message "Press any key"
				waitFor(shutdownProcess, "Press any key to continue");
				writer.write("y");
				writer.flush();
			} finally {
				writer.close();
			}
		}
		shutdownProcess.waitFor();
		if (shutdownProcess.exitValue() != 0) {
			log.info("Failed to stop JBoss");
		}
	}

	private String createShutdownScript() {
		if (winSystem()) {
			return "/bin/shutdown.bat";
		}
		return "/bin/shutdown.sh";
	}

	private void deploy() throws Exception {
		for (Deployment deployment : getDeployments()) {
			deployment.deploy(getDeployDir());
			if (deployment.getWaitForMessage() != null) {
//                waitFor(jbossProcess, deployment.getWaitForMessage());
				waitFor(getTailProcess(), deployment.getWaitForMessage());
			} else {
				Thread.sleep(deployment.getWaitMillis());
			}
		}
		deploymentComplete = true;

		// close the tail process so it doesn't overload
		tailProcess.getInputStream().close();
	}

	private void undeploy() throws Exception {
		for (Deployment deployment : getDeployments()) {
			deployment.undeploy(getDeployDir());
		}
	}

	public String getDeployDir() {
		return serverHome + "/server/" + configuration + "/deploy/";
	}

	protected static class SysoutLog {
		public void info(String msg) {
			System.out.println("--- " + msg);
		}
	}

	private void scheduleTimeout() {
		new Thread(
				new Runnable() {

					@Override
					public void run() {
						try {
							Thread.sleep(((long) getServerProperties().getDeploymentTimeoutMinutes()) * MILLISECONDS_IN_MINUTE);
							if (!deploymentComplete) {
								System.out.println("Timeout, shutting down JBoss");
								shutdown();
								throw new RuntimeException("Timeout");
							}
						} catch (InterruptedException e) {
							// do nothing
						} catch (Exception e) {
							throw new RuntimeException(e);
						}
					}
				}
		).start();
	}

	protected void publishLog() throws IOException {
		File tmpLogFile = File.createTempFile("jboss_log", ".txt");
		File log = new File(getServerLogPath());
		FileUtils.copyFile(log, tmpLogFile);

		System.out.println("##teamcity[publishArtifacts '" + tmpLogFile.getAbsolutePath() + "']");

		deleteLog();
	}

	private void deleteLog() {
		boolean deleted = new File(getServerLogPath()).delete();
		if (!deleted) {
			log.info("Unable to delete JBoss log");
		}
	}
}
