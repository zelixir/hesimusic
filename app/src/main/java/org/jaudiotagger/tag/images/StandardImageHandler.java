package org.jaudiotagger.tag.images;

import java.io.IOException;

/**
 Image Handling used when running on standard JVM

 Note: Image processing functionality has been removed as it depends on
 javax.imageio and java.awt which are not available on Android.
 */
public class StandardImageHandler implements ImageHandler
{
    private static StandardImageHandler instance;

    public static StandardImageHandler getInstanceOf()
    {
        if(instance==null)
        {
            instance = new StandardImageHandler();
        }
        return instance;
    }

    private StandardImageHandler()
    {

    }

    /**
     * Resize the image until the total size require to store the image is less than maxsize
     * @param artwork
     * @param maxSize
     * @throws IOException
     */
    public void reduceQuality(Artwork artwork, int maxSize) throws IOException
    {
        throw new UnsupportedOperationException("Image processing not supported");
    }
     /**
     * Resize image
      * @param artwork
      * @param size
      * @throws java.io.IOException
      */
    public void makeSmaller(Artwork artwork,int size) throws IOException
    {
        throw new UnsupportedOperationException("Image processing not supported");
    }

    public boolean isMimeTypeWritable(String mimeType)
    {
        throw new UnsupportedOperationException("Image processing not supported");
    }

    /**
     *  Write image data as required format
     *
     * @param imageData
     * @param mimeType
     * @return
     * @throws IOException
     */
    public byte[] writeImage(byte[] imageData, String mimeType) throws IOException
    {
        throw new UnsupportedOperationException("Image processing not supported");
    }

    /**
     *
     * @param imageData
     * @return
     * @throws IOException
     */
    public byte[] writeImageAsPng(byte[] imageData) throws IOException
    {
        throw new UnsupportedOperationException("Image processing not supported");
    }

    /**
     * Show read formats
     *
     * On Windows supports png/jpeg/bmp/gif
     */
    public void showReadFormats()
    {
        throw new UnsupportedOperationException("Image processing not supported");
    }

    /**
     * Show write formats
     *
     * On Windows supports png/jpeg/bmp
     */
    public void showWriteFormats()
    {
        throw new UnsupportedOperationException("Image processing not supported");
    }
}
