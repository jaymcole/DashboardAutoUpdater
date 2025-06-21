
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class Main {
    // Static field to store the Dashboard process
    private static Process dashboardProcess = null;

    // Method to check if Dashboard is running
    public static boolean isDashboardRunning() {
        return dashboardProcess != null && dashboardProcess.isAlive();
    }

    // Method to stop the Dashboard process
    public static void stopDashboard() {
        if (dashboardProcess != null && dashboardProcess.isAlive()) {
            dashboardProcess.destroy();
            try {
                dashboardProcess.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            dashboardProcess = null;
        }
    }

    private static final String REMOTE_NAME = "origin";
    private static final String REPO_URL = "https://github.com/jaymcole/Dashboard.git";

    public static void main(String[] args) {
        File saveDir = getCrossplatformSaveDirectory();
        File repositoryDir = new File(saveDir, ".git");
        Git git = null;

        try {
            // Clone the repository if it doesn't exist
            if (!repositoryDir.exists()) {
                try {
                    cloneRepository(REPO_URL, saveDir.getAbsolutePath());
                } catch (GitAPIException e) {
                    System.err.println("Error cloning repository: " + e.getMessage());
                    e.printStackTrace();
                    return;
                }
            }

            git = Git.open(repositoryDir);
            pullLatestVersion(git);
            compileDashboardRepo(git);
            launchDashboardApp(git);

            boolean repoNeedsUpdate = false;
            while(true) {
                try {
                    repoNeedsUpdate = checkForUpdates(git);
                    if(repoNeedsUpdate) {
                        System.out.println("Repository needs update");
                        pullLatestVersion(git);
                        printCommit(getLatestCommit(git));
                        compileDashboardRepo(git);
                        stopDashboard();
                        launchDashboardApp(git);
                    } else {
                        System.out.println("Repository is up to date");
                        if (!isDashboardRunning()) {
                            launchDashboardApp(git);
                        }
                    }
                    Thread.sleep(60000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break; // Exit loop when interrupted
                }
            }
        } catch (IOException | GitAPIException e) {
            System.err.println("Error checking repository: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (git != null) {
                git.close();
            }
        }
    }

    private static void compileDashboardRepo(Git git) {
        // Get the repository directory
        File repoDir = git.getRepository().getDirectory().getParentFile();
        System.out.println("Working directory: " + repoDir.getAbsolutePath());
        
        // Check for pom.xml
        File pomFile = new File(repoDir, "pom.xml");
        if (!pomFile.exists()) {
            throw new RuntimeException("pom.xml not found in repository");
        }
        
        // Create ProcessBuilder for Maven
        ProcessBuilder processBuilder = new ProcessBuilder();
        if (System.getProperty("os.name").toLowerCase().contains("windows")) {
            processBuilder.command("cmd.exe", "/c", "mvn", "clean", "package", "-DskipTests");
        } else {
            processBuilder.command("mvn", "clean", "package", "-DskipTests");
        }
        
        // Set the working directory to the Dashboard directory
        processBuilder.directory(repoDir);
        
        // Redirect error stream to output stream for easier handling
        processBuilder.redirectErrorStream(true);

        // Set the working directory to the Dashboard directory
        processBuilder.directory(repoDir);

        // Redirect error stream to output stream for easier handling
        processBuilder.redirectErrorStream(true);

        // Execute the process and handle output and errors
        try {
            Process process = processBuilder.start();

            // Read the output
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
            );

            // Print the output
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }

            // Wait for the process to complete
            int exitCode = process.waitFor();
            System.out.println("Gradle build completed with exit code: " + exitCode);

            if (exitCode != 0) {
                throw new RuntimeException("Gradle build failed with exit code: " + exitCode);
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Error executing Gradle build", e);
        }
    }

    private static void launchDashboardApp(Git git) {
        File repoDir = git.getRepository().getDirectory().getParentFile();
        System.out.println("Launching Dashboard from: " + repoDir.getAbsolutePath());

        // Get appropriate JAR path based on OS
        String jarPath = System.getProperty("os.name").toLowerCase().contains("windows") ? 
            "lwjgl3\\build\\libs\\Dashboard-1.0.0.jar" : 
            "lwjgl3/build/libs/Dashboard-1.0.0.jar";

        // Create ProcessBuilder to run the Dashboard application
        ProcessBuilder processBuilder = new ProcessBuilder(
            "java", "-jar", jarPath
        );

        // Set the working directory
        processBuilder.directory(repoDir);

        // Redirect error stream to output stream
        processBuilder.redirectErrorStream(true);

        // Start the process in a new thread
        Thread dashboardThread = new Thread("Dashboard Process Thread") {
            @Override
            public void run() {
                try {
                    Process process = processBuilder.start();
                    dashboardProcess = process; // Store the process reference

                    // Create output reader in a separate thread
                    Thread outputReader = new Thread("Dashboard Output Reader") {
                        @Override
                        public void run() {
                            try (BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(process.getInputStream())
                            )) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    System.out.println("[Dashboard] " + line);
                                }
                            } catch (IOException e) {
                                System.err.println("Error reading Dashboard output: " + e.getMessage());
                            }
                        }
                    };
                    outputReader.setDaemon(true);
                    outputReader.start();

                    // Wait for the process to complete
                    int exitCode = process.waitFor();
                    System.out.println("Dashboard application exited with code: " + exitCode);
                    dashboardProcess = null;

                } catch (IOException | InterruptedException e) {
                    System.err.println("Error launching Dashboard: " + e.getMessage());
                    e.printStackTrace();
                    if (dashboardProcess != null) {
                        dashboardProcess = null;
                    }
                }
            }
        };

        // Start the dashboard thread as a daemon so it doesn't prevent JVM shutdown
        dashboardThread.setDaemon(true);
        dashboardThread.start();

        // Return immediately after starting the process
        System.out.println("Dashboard application launched successfully");
    }

    /**
     * Gets a cross-platform save directory for the repository
     * @return A File object representing the save directory
     */
    private static File getCrossplatformSaveDirectory() {
        // Get user's home directory
        String userHome = System.getProperty("user.home");

        // Create a directory structure that works on both Windows and Linux
        // We'll use .dashboard-auto-updater in the user's home directory
        File saveDir = new File(userHome, "dashboard-auto-updater");

        // Create the directory if it doesn't exist
        if (!saveDir.exists()) {
            saveDir.mkdirs();
        }

        return saveDir;
    }

    private static boolean checkForUpdates(Git git) {
        try {
            // Get the current branch
            String currentBranch = git.getRepository().getBranch();

            // Fetch the latest changes from remote
            git.fetch().setRemote(REMOTE_NAME).call();

            // Get the latest local commit
            ObjectId localHead = git.getRepository().resolve("HEAD");

            // Get the latest remote commit
            ObjectId remoteHead = git.getRepository().resolve("refs/remotes/origin/" + currentBranch);

            // Compare the commits
            return !localHead.equals(remoteHead);
        } catch (GitAPIException | IOException e) {
            System.err.println("Error checking for updates: " + e.getMessage());
            return false;
        }
    }

    private static void pullLatestVersion(Git git) throws GitAPIException {
        System.out.println("Pulling latest changes from remote...");
        PullCommand pullCommand = git.pull();
        pullCommand.setRemote(REMOTE_NAME);

        PullResult result = pullCommand.call();

        if (result.isSuccessful()) {
            System.out.println("Successfully pulled latest changes");
        } else {
            System.err.println("Pull failed");
            System.err.println("Merge result: " + result.getMergeResult());
        }
    }

    /**
     * Clones a Git repository from a URL to a specified directory
     * @param repositoryUrl The URL of the Git repository to clone
     * @param localPath The local directory path where the repository should be cloned
     * @return The cloned Git repository object
     * @throws GitAPIException If there's an error during the clone operation
     */
    private static Git cloneRepository(String repositoryUrl, String localPath)
            throws GitAPIException {
        try {

            String username = null;
            String password = null;

            System.out.println("Cloning repository from: " + repositoryUrl);
            System.out.println("Local path: " + localPath);

            Git.cloneRepository()
                    .setURI(repositoryUrl)
                    .setDirectory(new File(localPath))
                    .setCloneAllBranches(true)
                    .setCredentialsProvider(
                            (username != null && password != null)
                                    ? new UsernamePasswordCredentialsProvider(username, password)
                                    : null)
                    .call();

            return Git.open(new File(localPath + "/.git"));
        } catch (IOException e) {
            throw new RuntimeException("Error opening cloned repository: " + e.getMessage(), e);
        }
    }

    private static RevCommit getLatestCommit(Git git) throws IOException {
        // Get the current branch
        String currentBranch = git.getRepository().getBranch();
        System.out.println("Current branch: " + currentBranch);

        // Get the latest commit from remote
        String remoteBranchName = "refs/remotes/" + REMOTE_NAME + "/" + currentBranch;
        Ref remoteBranchRef = git.getRepository().getRef(remoteBranchName);

        if (remoteBranchRef != null) {
            try {
                RevWalk revWalk = new RevWalk(git.getRepository());
                return revWalk.parseCommit(remoteBranchRef.getObjectId());
            } catch (IOException e) {
                System.err.println("Error with RevWalk: " + e.getMessage());
                e.printStackTrace();
            }
        }
        return null;
    }

    private static void printCommit(RevCommit commit) throws IOException {
        // Format the commit date
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String commitDate = dateFormat.format(new Date(commit.getCommitTime() * 1000L));

        // Get the commit message
        String commitMessage = commit.getFullMessage();

        // Print the latest version information
        System.out.println("\nLatest version information:");
        System.out.println("Commit Hash: " + commit.getName());
        System.out.println("Author: " + commit.getAuthorIdent().getName());
        System.out.println("Date: " + commitDate);
        System.out.println("Message: " + commitMessage);
    }
}