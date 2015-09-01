package hudson.plugins.rally_build;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
    private String workspaceRef;
    private String artifactBaseUrl;

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

    public String getWorkspaceRef() {
      return workspaceRef;
    }

    public void setWorkspaceRef(String workspaceRef) {
      this.workspaceRef = workspaceRef;
    }

    public String getArtifactBaseUrl() {
      return artifactBaseUrl;
    }

    public void setArtifactBaseUrl(String artifactBaseUrl) {
      this.artifactBaseUrl = artifactBaseUrl;
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject formData) {
      apiKey = formData.getString("rally_api_key");
      url = formData.getString("rally_url");
      artifactBaseUrl = formData.getString("rally_artifact_base_url");
      workspaceRef = formData.getString("rally_workspace_ref");

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

    /*
    RallyBuildData data = new RallyBuildData();
    try {
      data.addAtrifact(new RallyArtifact(RallyArtifact.Type.DEFECT, "DE1234", "Super bad defect!", new URI("https://www.google.co.uk")));
    } catch (URISyntaxException e) {
      e.printStackTrace();
    }

    build.addAction(data);

    */

    URI rally_uri;

    RallyBuildData data = new RallyBuildData();

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

      QueryRequest projectReq = new QueryRequest("project");
      projectReq.setQueryFilter(new QueryFilter("Name", "=", project));
      projectReq.setWorkspace(getDescriptor().getWorkspaceRef());
      projectReq.setFetch(new Fetch());
      projectReq.setLimit(1);

      QueryResponse rsp = api.query(projectReq);

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

      QueryRequest buildDefReq = new QueryRequest(projectObj.getAsJsonObject("BuildDefinitions"));
      buildDefReq.setQueryFilter(new QueryFilter("Name", "=", buildName));
      buildDefReq.setWorkspace(getDescriptor().getWorkspaceRef());
      buildDefReq.setFetch(new Fetch());
      buildDefReq.setLimit(1);

      rsp = api.query(buildDefReq);

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

      QueryRequest chengeSetReq = new QueryRequest("changeset");
      chengeSetReq.setWorkspace(getDescriptor().getWorkspaceRef());
      chengeSetReq.setFetch(new Fetch());
      chengeSetReq.setLimit(1);

      JsonArray changeSets = new JsonArray();

      String artifactBaseUrl = getDescriptor().getArtifactBaseUrl();
      if(artifactBaseUrl.endsWith("/")) artifactBaseUrl = artifactBaseUrl.substring(0, artifactBaseUrl.length() - 1);

      for(ChangeLogSet<? extends ChangeLogSet.Entry> set : build.getChangeSets()){
        for(ChangeLogSet.Entry change : set){
          chengeSetReq.setQueryFilter(new QueryFilter("Revision", "=", change.getCommitId()));
          rsp = api.query(chengeSetReq);

          if(rsp.wasSuccessful() && rsp.getTotalResultCount() > 0){
            JsonObject changeSet = rsp.getResults().get(0).getAsJsonObject();
            changeSets.add(changeSet);

            if(changeSet.getAsJsonObject("Artifacts").get("Count").getAsInt() > 0) {
              JsonObject artifactsCollection = changeSet.getAsJsonObject("Artifacts");

              QueryRequest artifactReq = new QueryRequest(artifactsCollection);
              artifactReq.setWorkspace(getDescriptor().getWorkspaceRef());
              artifactReq.setFetch(new Fetch());
              rsp = api.query(artifactReq);

              if(rsp.wasSuccessful() && rsp.getTotalResultCount() > 0) {
                for(JsonElement listElm : rsp.getResults()) {
                  JsonObject artifact = listElm.getAsJsonObject();

                  if(artifact != null) {
                    String objectId = artifact.get("ObjectID").getAsString();

                    RallyArtifact artifactData = null;

                    switch(artifact.get("_type").getAsString()) {
                      case "Defect": {
                        String url = artifactBaseUrl + "/detail/defect/" + objectId;

                        artifactData = new RallyArtifact(RallyArtifact.Type.DEFECT,
                                objectId,
                                artifact.get("FormattedID").getAsString(),
                                artifact.get("_refObjectName").getAsString(),
                                url);
                        break;
                      }
                      case "HierarchicalRequirement": {
                        String url = artifactBaseUrl + "/detail/userstory/" + objectId;

                        artifactData = new RallyArtifact(RallyArtifact.Type.USER_STORY,
                                objectId,
                                artifact.get("FormattedID").getAsString(),
                                artifact.get("_refObjectName").getAsString(),
                                url);

                      }
                    }

                    if(artifactData != null) data.addAtrifact(artifactData);
                  }
                }
              }
            }
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

      build.addAction(data);

      return true;
    }

  }

  private transient final Logger logger;
}
