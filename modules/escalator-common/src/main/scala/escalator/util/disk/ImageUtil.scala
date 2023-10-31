package escalator.util.disk

import javax.imageio.ImageIO
import de.androidpit.colorthief._

// import java.awt.image.BufferedImage
import java.io.File
// import java.io.IOException
// import java.nio.file.Files
// import java.nio.file.Paths

object ImageUtil {

	def getDominantHexColor(file: String): String = {
		val img = ImageIO.read(new File(file))
		val colorMap = ColorThief.getColorMap(img, 5)
        val dominantColor: MMCQ.VBox = colorMap.vboxes.get(0)
		val rgb: Array[Int] = dominantColor.avg(false)
        createRGBHexString(rgb)
	}	

	def getDominantRGBColor(file: String): String = {
		val img = ImageIO.read(new File(file))
		val colorMap = ColorThief.getColorMap(img, 5)
        val dominantColor: MMCQ.VBox = colorMap.vboxes.get(0)
		val rgb: Array[Int] = dominantColor.avg(false)
        createRGBString(rgb)
	}	

 	def createRGBString(rgb: Array[Int]): String = {
    	"rgb(" + rgb(0) + "," + rgb(1) + "," + rgb(2) + ")"
    }

    def createRGBHexString(rgb: Array[Int]): String = {
        var rgbHex = java.lang.Integer.toHexString(rgb(0) << 16 | rgb(1) << 8 | rgb(2))

        val length = rgbHex.length
        if (length < 6) {
            rgbHex = "00000".substring(0, 6 - length) + rgbHex
        }

        "#" + rgbHex
    }

}
