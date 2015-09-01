package hudson.plugins.rally_build;

import hudson.model.Action;

import java.io.Serializable;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class RallyBuildData implements Action, Serializable{

  private static final long serialVersionUID = 1L;

  Set<RallyArtifact> defects;
  Set<RallyArtifact> userStories;

  RallyBuildData() {
    defects = new CopyOnWriteArraySet<>();
    userStories = new CopyOnWriteArraySet<>();
  }

  public void addAtrifact(RallyArtifact artifact) {
    switch(artifact.getType()) {
      case DEFECT:
        defects.add(artifact);
        break;
      case USER_STORY:
        userStories.add(artifact);
        break;
    }
  }

  public Set<RallyArtifact> getArtifacts() {
    Set<RallyArtifact> res = new CopyOnWriteArraySet<>();

    res.addAll(defects);
    res.addAll(userStories);

    return res;
  }

  public Set<RallyArtifact> getDefects() {
    return defects;
  }

  public Set<RallyArtifact> getUserStories() {
    return userStories;
  }

  @Override
  public String getIconFileName() {
    return jenkins.model.Jenkins.RESOURCE_PATH+"/plugin/rally_build/rally.png";
  }

  @Override
  public String getDisplayName() {
    return "Rally";
  }

  @Override
  public String getUrlName() {
    return "rally";
  }

}
