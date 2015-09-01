package hudson.plugins.rally_build;

public class RallyArtifact {

  public enum Type {
    DEFECT,
    USER_STORY
  }

  private final Type type;
  private final String id;
  private final String formattedId;
  private final String title;
  private final String link;

  RallyArtifact(Type type, String id, String formattedId, String title, String link) {
    this.type = type;
    this.id = id;
    this.formattedId = formattedId;
    this.title = title;
    this.link = link;
  }

  public Type getType() {
    return type;
  }

  public String getId() {
    return id;
  }

  public String getFormattedId() {
    return formattedId;
  }

  public String getTitle() {
    return title;
  }

  public String getLink() {
    return link;
  }

  public String toString() {
    return id + " - " + title;
  }

  @Override
  public boolean equals(Object obj) {
    return  obj != null &&
            obj instanceof RallyArtifact &&
            type == ((RallyArtifact)obj).type &&
            id.equals(((RallyArtifact)obj).id);
  }
}
