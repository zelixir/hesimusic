package org.jaudiotagger.tag.images;

import java.io.IOException;

/**
 Image Handling to to use when running on Android

 TODO need to provide Android compatible implementations
 */
public class AndroidImageHandler implements ImageHandler
{
    private static AndroidImageHandler instance;

    public static AndroidImageHandler getInstanceOf()
    {
        if(instance==null)
        {
            instance = new AndroidImageHandler();
        }
        return instance;
    }

    private AndroidImageHandler()
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
        throw new UnsupportedOperationException();
    }
     /**
     * Resize image
      * @param artwork
      * @param size
      * @throws java.io.IOException
      */
    public void makeSmaller(Artwork artwork,int size) throws IOException
    {
        throw new UnsupportedOperationException();
    }

    public boolean isMimeTypeWritable(String mimeType)
    {
        throw new UnsupportedOperationException();
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
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param imageData
     * @return
     * @throws IOException
     */
    public byte[] writeImageAsPng(byte[] imageData) throws IOException
    {
        throw new UnsupportedOperationException();
    }

    /**
     * Show read formats
     *
     * On Windows supports png/jpeg/bmp/gif
     */
    public void showReadFormats()
    {
        throw new UnsupportedOperationException();
    }

    /**
     * Show write formats
     *
     * On Windows supports png/jpeg/bmp
     */
    public void showWriteFormats()
    {
        throw new UnsupportedOperationException();
    }
}
