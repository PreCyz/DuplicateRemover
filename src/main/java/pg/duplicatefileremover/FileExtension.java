package pg.duplicatefileremover;

public enum FileExtension {
    JPEG("jpeg"),
    JPG("jpg"),
    PNG("png"),
    MOV("mov"),
    GP3("gp3"),
    MP4("MP4"),
    TXT("txt");

    public final String extension;
    FileExtension(String extension) {
        this.extension = extension;
    }
}
