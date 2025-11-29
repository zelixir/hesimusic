package org.jaudiotagger.tag.images;

import java.io.IOException;

/**
 * Image Handler
 */
public interface ImageHandler
{
    public void reduceQuality(Artwork artwork, int maxSize) throws IOException;
    public void makeSmaller(Artwork artwork,int size) throws IOException;
    public boolean isMimeTypeWritable(String mimeType);
    public byte[] writeImage(byte[] imageData, String mimeType) throws IOException;
    public byte[] writeImageAsPng(byte[] imageData) throws IOException;
    public void showReadFormats();
    public void showWriteFormats();
}
