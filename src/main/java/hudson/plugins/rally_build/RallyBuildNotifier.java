package hudson.plugins.rally_build;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.rallydev.rest.RallyRestApi;
import com.rallydev.rest.request.CreateRequest;
import com.rallydev.rest.request.QueryRequest;
import com.rallydev.rest.response.CreateResponse;
import com.rallydev.rest.response.QueryResponse;
import com.rallydev.rest.util.Fetch;
import com.rallydev.rest.util.QueryFilter;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.scm.ChangeLogSet;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RallyBuildNotifier extends Notifier {

  @Extension
  public static class RallyBuildNotifierDescriptor extends BuildStepDescriptor<Publisher> implements Serializable {

    private static final long serialVersionUID = 1L;

    private String apiKey;
    private String url;

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }

    public String getUrl() {
      return url;
    }

    public void setUrl(String url) {
      this.url = url;
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject formData) {
      apiKey = formData.getString("rally_api_key");
      url = formData.getString("rally_url");
      save();
      return true;
    }

    public RallyBuildNotifierDescriptor() {
      super(RallyBuildNotifier.class);
      load();
    }

    @Override
    public boolean isApplicable(Class jobType) {
      return FreeStyleProject.class.isAssignableFrom(jobType);
    }

    @Override
    public String getDisplayName() {
      return "Publish in Rally";
    }

  }


  private String project;
  private String buildName;
  private String buildNumber;

  public String getProject() {
    return project;
  }

  public String getBuildName() {
    return buildName;
  }

  public String getBuildNumber() {
    return buildNumber;
  }


  @DataBoundConstructor
  public RallyBuildNotifier(String project, String buildName, String buildNumber) {
    this.project = project;
    this.buildName = buildName;
    this.buildNumber = buildNumber;

    logger = Logger.getLogger("hudson.plugins.rally_build.RallyBuildNotifier");
  }

  @Override
  public RallyBuildNotifierDescriptor getDescriptor() {
    return (RallyBuildNotifierDescriptor) super.getDescriptor();
  }

  @Override
  public boolean needsToRunAfterFinalized() {
    return true;
  }

  @Override
  public BuildStepMonitor getRequiredMonitorService() {
    return BuildStepMonitor.NONE;
  }

  @Override
  public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {

    URI rally_uri;

    try {
      rally_uri = new URI(getDescriptor().getUrl());
    } catch (URISyntaxException e) {
      logger.log(Level.SEVERE, "Failed to parse Rally API URL.", e);
      return false;
    }

    try (RallyRestApi api = new RallyRestApi(rally_uri, getDescriptor().getApiKey())) {

      api.setApplicationName("RallyBuildNotifer");

      //
      // Get the project.
      //

      QueryRequest req = new QueryRequest("project");
      req.setQueryFilter(new QueryFilter("Name", "=", project));
      req.setFetch(new Fetch("ObjectID"));
      req.setLimit(1);

      QueryResponse rsp = api.query(req);

      if (!rsp.wasSuccessful() || rsp.getTotalResultCount() < 1) {
        logger.warning("Failed to get Rally Project: " + project);
        return false;
      }

      JsonObject projectObj = rsp.getResults().get(0).getAsJsonObject();

      JsonPrimitive projectRef = projectObj.get("_ref").getAsJsonPrimitive();

      String projectId = projectObj.get("ObjectID").getAsString();


      //
      // Get the build definition to add this build to.
      //

      req = new QueryRequest("project/" + projectId + "/BuildDefinitions");
      req.setQueryFilter(new QueryFilter("Name", "=", buildName));
      req.setFetch(new Fetch());
      req.setLimit(1);

      rsp = api.query(req);

      if (!rsp.wasSuccessful()) {
        logger.warning("Failed to get Rally Build Definition: " + buildName);
        return false;
      }

      JsonPrimitive buildDefinition;

      if(rsp.getTotalResultCount() < 1) {
        JsonObject newDefinition = new JsonObject();
        newDefinition.add("Name", new JsonPrimitive(buildName));
        newDefinition.add("Project", projectRef);
        CreateRequest createRequest = new CreateRequest("builddefinition", newDefinition);
        CreateResponse createResponse = api.create(createRequest);

        buildDefinition = createResponse.getObject().get("_ref").getAsJsonPrimitive();

        if(!createResponse.wasSuccessful()) {
          logger.warning("Failed to create Rally Build Definition: " + buildName);
          return false;
        }
      } else {
        buildDefinition = rsp.getResults().get(0).getAsJsonObject().get("_ref").getAsJsonPrimitive();
      }

      req = new QueryRequest("changeset");
      req.setFetch(new Fetch());
      req.setLimit(1);

      JsonArray changeSets = new JsonArray();

      for(ChangeLogSet<? extends ChangeLogSet.Entry> set : build.getChangeSets()){
        for(ChangeLogSet.Entry change : set){
          req.setQueryFilter(new QueryFilter("Revision", "=", change.getCommitId()));
          rsp = api.query(req);

          if(rsp.wasSuccessful() && rsp.getTotalResultCount() > 0){
            changeSets.add(rsp.getResults().get(0).getAsJsonObject());
          }
        }
      }

      //
      // Create the new build.
      //


      JsonPrimitive buildStatus;

      if(Result.SUCCESS == build.getResult()) {
        buildStatus = new JsonPrimitive("SUCCESS");
      } else if(Result.FAILURE == build.getResult() ||
                Result.UNSTABLE == build.getResult()) {
        buildStatus = new JsonPrimitive("FAILURE");
      } else {
        buildStatus = new JsonPrimitive("UNKNOWN");
      }

      TimeZone zone = TimeZone.getTimeZone("UTC");
      SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'");
      formatter.setTimeZone(zone);

      //
      // Expand variables in build number.
      //

      String expandedBuildNumber = build.getEnvironment(listener).expand(buildNumber);

      JsonObject newBuild = new JsonObject();

      newBuild.add("Number", new JsonPrimitive(expandedBuildNumber));
      newBuild.add("Status", buildStatus);
      newBuild.add("Uri", new JsonPrimitive(Jenkins.getActiveInstance().getRootUrl() + build.getUrl()));
      newBuild.add("Start", new JsonPrimitive(formatter.format(build.getTime())));
      newBuild.add("Duration", new JsonPrimitive(build.getDuration()/1000.0));
      newBuild.add("BuildDefinition", buildDefinition);

      if(changeSets.size() > 0) {
        newBuild.add("Changesets", changeSets);
      }

      CreateRequest createReq = new CreateRequest("build", newBuild);
      CreateResponse createRsp = api.create(createReq);

      if (!createRsp.wasSuccessful()) {
        logger.warning("Failed to create Rally Build: " + newBuild.toString());
        return false;
      }

      return true;
    }
  }

  private transient final Logger logger;
}
