package com.vrondakis.zap;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;

import hudson.model.Job;
import org.apache.commons.io.IOUtils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;

import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Recorder;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

/**
 * ZapArchiver Main zap class, handles generating report.
 */

public class ZapArchive extends Recorder {
    private static final String RAW_REPORT_FILENAME = "zap-raw.json";
    private static final String RAW_OLD_REPORT_FILENAME = "zap-raw-old.json";
    private static final String FALSE_POSITIVES_FILENAME = "zap-false-positives.json";
    private static final String ALERT_COUNT_FILENAME = "alert-count.json";
    private static final String JSON_SITE_KEY = "site";
    private static final String JSON_ALERTS_KEY = "alerts";

    /**
     * Saves index.html to the current build archive
     *
     * @param run The current build
     * @return success
     */
    private boolean saveStaticFiles(@Nonnull Run<?, ?> run) {
        try {
            String indexName = "index.html";
            String pluginName = "zap-jenkins-plugin";
            FilePath indexFile = new FilePath(new File(
                    Jenkins.getInstance().getPlugin(pluginName).getWrapper().baseResourceURL.getFile(), indexName));
            indexFile.copyTo(new FilePath(new File(run.getRootDir(), Constants.DIRECTORY_NAME + "/" + indexName)));
            return true;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Saves alert-count.json to the zapDir directory (build/x/zap).
     * @param zapDir The ZAP directory you wish to save to
     * @param run The build, given by Jenkins
     * @return success
     */
    private boolean saveAlertCount(File zapDir, @Nonnull Run<?, ?> run) {
        try {
            ZapAlertCount zapAlertCount = new ZapAlertCount();


            List<ZapFalsePositiveInstance> zapFalsePositiveInstances = getSavedFalsePositives(zapDir);

            List<ZapAlert> alerts = getSavedZapReport(zapDir);
            alerts.forEach(alert -> {
                int amountOfInstances = alert.getInstances().size();
                int falsePositives = amountOfInstances - alert.getFilteredOutFalsePositiveInstances(zapFalsePositiveInstances).size();
                amountOfInstances -= falsePositives;
                zapAlertCount.falsePositives += falsePositives;
                switch (alert.getRiskcode()) {
                    case "1":
                        zapAlertCount.lowAlerts += amountOfInstances;
                    case "2":
                        zapAlertCount.mediumAlerts += amountOfInstances;
                    case "3":
                        zapAlertCount.highAlerts += amountOfInstances;
                }
            });

            zapAlertCount.buildName = run.getDisplayName();


            Gson gson = new Gson();
            String json = gson.toJson(zapAlertCount);

            FilePath fp = new FilePath(new File(zapDir + "/" + ALERT_COUNT_FILENAME));
            fp.write(json, "UTF-8");

            return true;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Retrieves the ZAP report from ZAP and saves it in path
     *
     * @param path - Where to save the file
     * @return If it saved successfully or not
     */
    private boolean saveZapReport(File path, Run run) {
        try {
            ZapDriver zapDriver = ZapDriverController.getZapDriver(run);
            FilePath fp = new FilePath(new File(path.toString() + "/" + RAW_REPORT_FILENAME));
            if (zapDriver.getZapHost() == null || zapDriver.getZapPort() == 0)
                return false;

            URI uri = new URI("http", null, zapDriver.getZapHost(), zapDriver.getZapPort(), "/OTHER/core/other/jsonreport",
                    "formMethod=GET", null);

            InputStream response = Unirest.get(uri.toString()).asString().getRawBody();
            String res = IOUtils.toString(response, StandardCharsets.UTF_8);
            fp.write(res, "UTF-8");

            return true;
        } catch (URISyntaxException | IOException | UnirestException | InterruptedException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Retrieves the previous report and copies it to the new build
     *
     * @param path    The path to save the old report
     * @param oldPath The path of the old report
     */
    private void savePreviousZapReport(File path, File oldPath) {
        FilePath saveLocation = new FilePath(new File(path.toString() + "/" + RAW_OLD_REPORT_FILENAME));
        FilePath oldReport = new FilePath(new File(oldPath.toString() + "/" + RAW_REPORT_FILENAME));

        try {
            if (oldReport.exists()) {
                oldReport.copyTo(saveLocation);
            }
        } catch (Exception ignored) {
            // Probably it's the first build ran while using the plugin
        }
    }

    /**
     * Retrieves the false positives file (if available) from the workspace and saves it to the build
     *
     * @param falsePositivesFilePath the relative path to the false positives file
     * @param workspace              the workspace for the running build
     * @param taskListener           task listener, passed by jenkins
     */
    private void saveFalsePositives(String falsePositivesFilePath, FilePath workspace, @Nonnull TaskListener taskListener,
                                    File savePath) {
        try {
            if (workspace != null) {
                FilePath[] falsePositivesFiles = workspace.list(falsePositivesFilePath);
                if (falsePositivesFiles.length > 0) {
                    if (falsePositivesFiles.length > 1) {
                        taskListener.getLogger()
                                .println("zap: More than one file matched the provided false positives file path. Using: '"
                                        + falsePositivesFiles[0].getName() + "'.");
                    }
                    FilePath saveAsFile = new FilePath(new File(savePath.toString() + "/" + FALSE_POSITIVES_FILENAME));
                    falsePositivesFiles[0].copyTo(saveAsFile);
                }

            } else {
                taskListener.getLogger().println(
                        "zap: Failed to access workspace for false positives file, it may be on a non-connected slave. False positives will not be suppressed.");
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            taskListener.getLogger()
                    .println("zap: Failed to do archive false positives file. False positives will not be suppressed");
        }
    }

    /**
     * Helper function to open report file & parse the JSON
     *
     * @param path - The path of the report
     * @return The list of Zap Alerts from the JSON
     */
    private List<ZapAlert> getAlertsFromReportFile(FilePath path) {
        try {
            JSONObject report = JSONObject.fromObject(path.readToString());

            // Zap returns either an array of sites, or a single site as an object. Attempt to load as an array, then
            // fall back to object on fail
            JSONArray sites;
            try {
                sites = report.getJSONArray(JSON_SITE_KEY);
            } catch (JSONException e) {
                sites = new JSONArray();
                sites.add(report.getJSONObject(JSON_SITE_KEY));
            }

            // Iterate over all sites, and flatten alerts down to a single array
            List<ZapAlert> alerts = new ArrayList<>();
            for (Object site : sites) {
                String alertArrayString = JSONObject.fromObject(site).getJSONArray(JSON_ALERTS_KEY).toString();
                List<ZapAlert> siteAlerts = new Gson().fromJson(alertArrayString, new TypeToken<List<ZapAlert>>() {
                }.getType());
                alerts.addAll(siteAlerts);
            }
            return alerts;
        } catch (IOException | InterruptedException | JSONException e) {
            return Collections.emptyList();
        }
    }

    /**
     * Gets all the ZapAlerts saved in a ZAP report file
     *
     * @param path     - The path of the report
     * @return List of all the {@code ZapAlert}s from the report
     */
    private List<ZapAlert> getSavedZapReport(File path) {
        FilePath fp = new FilePath(new File(path.toString() + "/" + ZapArchive.RAW_REPORT_FILENAME));
        return getAlertsFromReportFile(fp);
    }

    /**
     * Gets the report of the last build, if it is not available try the previous built build, and if that isn't available get the
     * last successful build.
     *
     * @param run The run
     * @return The file path of the last available report, null if none are found
     */
    private Optional<File> getPreviousReportDir(Run<?, ?> run) {

        File zapBuildDir = getBuildDir(run.getPreviousBuild()).orElseGet(() -> getBuildDir(run.getPreviousBuiltBuild())
                .orElseGet(() -> getBuildDir(run.getPreviousSuccessfulBuild()).orElse(null)));

        return Optional.ofNullable(zapBuildDir);
    }

    /**
     * Gets the current builds false positives from a saved file
     *
     * @param path     - The path of the saved false positives file
     * @return List of each false positive detailed in the file
     */
    private List<ZapFalsePositiveInstance> getSavedFalsePositives(File path) {
        FilePath filePath = new FilePath(new File(path.toString() + "/" + ZapArchive.FALSE_POSITIVES_FILENAME));
        try {
            String fileContents = filePath.readToString();
            return new Gson().fromJson(fileContents, new TypeToken<List<ZapFalsePositiveInstance>>() {
            }.getType());
        } catch (IOException | InterruptedException | JSONException e) {
            return Collections.emptyList();
        }
    }

    /**
     * Gets the build directory from a run
     *
     * @param run Passed by Jenkins
     * @return Optional file
     */
    private Optional<File> getBuildDir(Run<?, ?> run) {
        if (run != null) {
            File buildDir = new File(run.getRootDir(), Constants.DIRECTORY_NAME);
            if (buildDir.exists())
                return Optional.of(buildDir);
        }

        return Optional.empty();
    }

    /**
     * Archives the current raw ZAP JSON report &amp; saves static files
     *
     * @param run          The current run, passed by jenkins
     * @param workspace    The current workspace path, passed by jenkins
     * @param taskListener Logging
     * @return Operation success
     */
    public boolean archiveRawReport(@Nonnull Run<?, ?> run, @Nonnull Job<?, ?> job, @Nonnull FilePath workspace, @Nonnull TaskListener taskListener,
                                    String falsePositivesFilePath) {
        File zapDir = new File(run.getRootDir(), Constants.DIRECTORY_NAME);
        // Create the zap directory in the workspace if it doesn't already exist
        if (!zapDir.exists()) {
            boolean mResult = zapDir.mkdir();
            if (!mResult) {
                taskListener.getLogger()
                        .println("zap: Could not create directory at " + zapDir.toURI().toString() + " (archiveRawReport)");
                return false;
            }
        }

        // Fetches the JSON report from ZAP and saves it
        boolean success = saveZapReport(zapDir, run) && saveStaticFiles(run) && saveAlertCount(zapDir, run);


        // Only show the graph if ZAP has been ran more than twice in the current builds
        AtomicInteger count = new AtomicInteger(0);
        job.getBuildsAsMap().forEach((k, v) -> {
            File zapDirectory = new File(v.getRootDir(), Constants.DIRECTORY_NAME);
            FilePath filePath = new FilePath(new File(zapDirectory.toString() + "/" + "alert-count.json"));

            try {
                if (filePath.exists()) {
                    count.getAndIncrement();
                }
            } catch (InterruptedException | IOException e) {
                // Just don't count this build
                e.printStackTrace();
            }
        });

        // If the report was retrieved and saved add the "ZAP scanning report" to the build
        // If it was not saved just add the graph (by hiding the button)
        ZapAction action = new ZapAction(run) {
            @Override
            public String getDisplayName() {
                return success ? super.getDisplayName() : null;
            }

            @Override
            public String getIconFileName() {
                return success ? super.getIconFileName() : null;
            }
        };

        if (count.get() > 0) run.addAction(action);

        if (!success)
            return false;

        // Fetches the false positives file (if it exists) and saves it
        saveFalsePositives(falsePositivesFilePath, workspace, taskListener, zapDir);

        // Saves the report of the previous build in the current builds workspace
        Optional<File> oldBuildZapDir = getPreviousReportDir(run);
        oldBuildZapDir.ifPresent(file -> savePreviousZapReport(zapDir, file));

        return true;
    }

    /**
     * Review the report, and fail the build according to given fail build parameters
     *
     * @param run      - The current run
     * @param listener - Logging
     * @return If the build should be failed
     */
    public boolean shouldFailBuild(Run<?, ?> run, TaskListener listener) {
        listener.getLogger().println("zap: Checking results...");
        ZapDriver zapDriver = ZapDriverController.getZapDriver(run);

        try {
            // Collect the alerts and false positives associated with this build
            File zapDir = new File(run.getRootDir(), Constants.DIRECTORY_NAME);
            List<ZapAlert> currentBuildAlerts = getSavedZapReport(zapDir);
            List<ZapFalsePositiveInstance> zapFalsePositiveInstances = getSavedFalsePositives(zapDir);
            Map<Integer, Integer> alertCounts = new HashMap<>();

            // Count the number of alert instances (filtering out false positives)
            currentBuildAlerts.forEach(alert -> {
                int riskCode = Integer.parseInt(alert.getRiskcode());
                int filteredInstancesCount = alert.getFilteredOutFalsePositiveInstances(zapFalsePositiveInstances).size();
                int newCount = alertCounts.containsKey(riskCode) ? alertCounts.get(riskCode) + filteredInstancesCount
                        : filteredInstancesCount;
                alertCounts.put(riskCode, newCount);
            });

            // Total amount of alert instances with a risk code more than 1
            alertCounts.put(Constants.ALL_ALERT,
                    (int) currentBuildAlerts.stream().filter(alert -> Integer.parseInt(alert.getRiskcode()) > 0).count());

            // Compare the fail build parameter to the amount of alerts in a certain category
            AtomicBoolean failBuild = new AtomicBoolean(false);
            zapDriver.getFailBuild().forEach((code, val) -> {
                if (val > 0 && alertCounts.get(code) >= val) {
                    failBuild.set(true);
                }
            });
            return failBuild.get();

        } catch (NullPointerException e) {
            listener.getLogger().println("zap: Could not determine whether the build has new alerts.");
            return false;
        }
    }

    // For Jenkins
    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return null;
    }
}