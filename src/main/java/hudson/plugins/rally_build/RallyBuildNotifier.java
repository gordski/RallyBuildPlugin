package hudson.plugins.rally_build;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.rallydev.rest.RallyRestApi;
import com.rallydev.rest.client.HttpClient;
import com.rallydev.rest.request.CreateRequest;
import com.rallydev.rest.request.GetRequest;
import com.rallydev.rest.request.QueryRequest;
import com.rallydev.rest.response.CreateResponse;
import com.rallydev.rest.response.QueryResponse;
import com.rallydev.rest.util.Fetch;
import com.rallydev.rest.util.QueryFilter;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
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

      String project_id = rsp.getResults().get(0).getAsJsonObject().get("ObjectID").getAsString();


      //
      // Get the build definition to add this build to.
      //

      req = new QueryRequest("project/" + project_id + "/BuildDefinitions");
      req.setQueryFilter(new QueryFilter("Name", "=", buildName));
      req.setFetch(new Fetch());
      req.setLimit(1);

      rsp = api.query(req);

      if (!rsp.wasSuccessful() || rsp.getTotalResultCount() < 1) {
        logger.warning("Failed to get Rally Build Definition: " + buildName);
        return false;
      }

      JsonPrimitive buildDefinition = rsp.getResults().get(0).getAsJsonObject().get("_ref").getAsJsonPrimitive();

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

      JsonObject newBuild = new JsonObject();

      newBuild.add("Number", new JsonPrimitive(buildNumber));
      newBuild.add("Status", buildStatus);
      newBuild.add("Uri", new JsonPrimitive(Jenkins.getActiveInstance().getRootUrl() + build.getUrl()));
      newBuild.add("Start", new JsonPrimitive(formatter.format(build.getTime())));
      newBuild.add("Duration", new JsonPrimitive(build.getDuration()/1000.0));
      newBuild.add("BuildDefinition", buildDefinition);

      CreateRequest createReq = new CreateRequest("build", newBuild);
      CreateResponse createRsp = api.create(createReq);

      if (!createRsp.wasSuccessful()) {
        logger.warning("Failed to create Rally Build: " + newBuild.toString());
        return false;
      }

      return true;
    }
  }

  private final Logger logger;
}
