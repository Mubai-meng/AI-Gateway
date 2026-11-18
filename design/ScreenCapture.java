import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** 验收用：截取桌面窗口区域。用法 java ScreenCapture.java out.png [x y w h] */
public class ScreenCapture {
    public static void main(String[] args) throws Exception {
        Rectangle r;
        if (args.length >= 5) {
            r = new Rectangle(Integer.parseInt(args[1]), Integer.parseInt(args[2]),
                    Integer.parseInt(args[3]), Integer.parseInt(args[4]));
        } else {
            r = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        }
        BufferedImage img = new Robot().createScreenCapture(r);
        ImageIO.write(img, "png", new File(args[0]));
        System.out.println("saved " + args[0] + " " + img.getWidth() + "x" + img.getHeight());
    }
}
