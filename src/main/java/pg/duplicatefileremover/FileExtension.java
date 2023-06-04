package pg.duplicatefileremover;

public enum FileExtension {
    JPEG("jpeg"),
    JPG("jpg"),
    PNG("png"),
    TXT("txt");

    public final String extension;
    FileExtension(String extension) {
        this.extension = extension;
    }
}
