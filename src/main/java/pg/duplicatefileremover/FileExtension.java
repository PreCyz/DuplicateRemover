package pg.duplicatefileremover;

public enum FileExtension {
    JPEG("jpeg"),
    JPG("jpg"),
    PNG("png"),
    GIF("gif"),
    BMP("bmp"),
    WEBP("webp"),
    MOV("mov"),
    THREE_GP("3gp"),
    MP4("mp4"),
    M4V("m4v");

    public final String extension;

    FileExtension(String extension) {
        this.extension = extension;
    }
}
