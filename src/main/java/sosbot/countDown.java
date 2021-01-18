package sosbot;

import com.squareup.gifencoder.GifEncoder;
import com.squareup.gifencoder.ImageOptions;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Slf4j
public class countDown {
    static int[][][] digits;
    static int[][][] getDigits() {
        if(digits==null) {
           digits=new int[10][][];
            for(int i=0;i<10;++i)
                try {
                    digits[i] = loadImageTo2D(ImageIO.read(countDown.class.getResource("/"+i + ".gif")));
                } catch(IOException e) {
                    log.error("image init error",e);
                }
        }
        return digits;
    }

    static byte[] getCountdown(int start,int secs) {

        ByteArrayOutputStream os= new ByteArrayOutputStream();
        try {
            GifEncoder ge = new GifEncoder(os,20,27,1);
            ImageOptions opt=new ImageOptions();
            opt.setLeft(0).setTop(0).setDelay(secs, TimeUnit.SECONDS);
            for(int i=start;i>=0;--i) {
                ge.addImage(getDigits()[i], opt);
            }
            ge.finishEncoding();
        } catch (IOException e) {
            log.error("gif write error", e);
        }
        return os.toByteArray();
    }

    private static int[][] loadImageTo2D(BufferedImage img)
    {
        int width = img.getWidth();
        int height = img.getHeight();
        int[][] pix = new int[height][width];
        for (int row = 0; row < height; row++)
            for (int col = 0; col < width; col++)
                pix[row][col] = img.getRGB(col, row);
        return pix;
    }
}
