package img

import java.io.File

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.stream.Materializer
import akka.stream.scaladsl.{FileIO, Flow}
import com.typesafe.config.{Config, ConfigFactory}
import javax.swing.JFrame
import net.sourceforge.tess4j.Tesseract
import org.bytedeco.javacpp.opencv_core._
import org.bytedeco.javacpp.opencv_imgcodecs.cvLoadImage
import org.bytedeco.javacpp.opencv_imgproc.{CV_AA, cvLine}
import org.bytedeco.javacv.{CanvasFrame, OpenCVFrameConverter}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class ImagePipeline(implicit actorSystem: ActorSystem, materializer: Materializer, executionContext: ExecutionContext) {
  type Line = (CvPoint, CvPoint)

  val error = 8

  val config: Config = ConfigFactory.load()

  val tesseract: Tesseract = new Tesseract
  tesseract.setDatapath(config.getString("tesseract.home"))


  def stream[T] = {
    Flow[((String, String), T)]
      .mapAsync(2) { case ((name, url), data) => downloadFile(name, url).map(_.map(_ -> data)) }
      .via(collectOption)
      .map { case (f, d) => readOriginalFile(f.getAbsolutePath).map(_ -> d) }
      .via(collectOption)
      .map { case (i, d) => cropImage(i).map(_ -> d) }
      .via(collectOption)
      .mapConcat { case (i, d) => splitImage(i).map(_ -> d) }
      .map { case (i, d) => stringParse(ocr(i)) -> d }
  }

  def stringParse(str: String) = {
    str.lines.toList match {
      case Nil => ""
      case h :: Nil => h
      case h :: s :: o =>
        val user = if(h.contains("] ")) {h.split("] ")(1)} else h
        val points = if(s.contains("Pts: ")) s.split("Pts: ")(1) else s
        s"$user --> $points"
    }
  }

  def downloadFile(filename: String, url: String): Future[Option[File]] = {
    import java.io.File
    val res = Http().singleRequest(HttpRequest(uri = url))
    res.flatMap { response =>
      val temp: File = File.createTempFile("temp-", filename)
      response.entity.dataBytes.runWith(FileIO.toFile(temp)).map(_ => Option(temp))
    }.recover { case _ => None }
  }

  def readOriginalFile(file: String) = {
    Try(cvLoadImage(file, 1)).toOption
  }

  def cropImage(src: IplImage): Option[IplImage] = {
    Try {
      val vertBigLines = getLines(src, 50).filter(isVertical(error)).sortBy(distance).reverse.take(4)
      getInnerVerticalLines(vertBigLines).map(cropByVerticalLines(src, _))
    }.toOption.flatten
  }

  def splitImage(cropedImg: IplImage): List[IplImage] = {
    val imgSize: CvSize = cvGetSize(cropedImg)
    val horBigLines = getLines(cropedImg, 15)
      .filter(isHorizontal(error))
      .filter(horizontalBigLines(imgSize))

    pairHorizontalLines(horBigLines)
      .filter(filterByHeight(cropedImg.height / 10, cropedImg.height / 10 / 2))
      .map(cropByHorizontalLines(cropedImg, _))
      .toList

  }

  def collectOption[T]: Flow[Option[T], T, NotUsed] = Flow[Option[T]].collect { case Some(t) => t }


  def doAll = {

    val infile = "in.png"
    val src: IplImage = cvLoadImage(infile, 1)
    val imgSize: CvSize = cvGetSize(src)

    val vertBigLines = getLines(src, 50).filter(isVertical(error)).sortBy(distance).reverse.take(4)

    printLinesToImage(src, vertBigLines)
    showImg(src, "source")

    getInnerVerticalLines(vertBigLines).fold {
      println(":(")
    } { innerLines =>
      val cropedImg = cropByVerticalLines(src, innerLines)
      val imgSize: CvSize = cvGetSize(cropedImg)
      val horBigLines = getLines(cropedImg, 15)
        .filter(isHorizontal(error))
        .filter(horizontalBigLines(imgSize))

      val smallImgs = pairHorizontalLines(horBigLines)
        .filter(filterByHeight(cropedImg.height / 10, cropedImg.height / 10 / 2))
        .map(cropByHorizontalLines(cropedImg, _))

      smallImgs.zipWithIndex.foreach { x =>
        showImg(x._1, x._2.toString)
        println("+" * 5)
        println(x._2.toString)
        println(ocr(x._1))
      }

      printLinesToImage(cropedImg, horBigLines, r = false, b = true)
      showImg(cropedImg, "cropedImg")
    }
  }

  def printLinesToImage(img: IplImage, lines: Seq[Line], r: Boolean = true, g: Boolean = false, b: Boolean = false) = {
    lines.foreach { case (pt1, pt2) =>
      cvLine(img, pt1, pt2, new CvScalar(if (b) 255 else 0, if (g) 255 else 0, if (r) 255 else 0, 255), 3, CV_AA, 0)
    }
  }

  def ocr(img: IplImage) = {
    tesseract.doOCR(iplImageToBufferedImage(img))
  }

  def getLines(src: IplImage, jumpTreshold: Int) = {
    import org.bytedeco.javacpp._
    import org.bytedeco.javacpp.opencv_core._
    import org.bytedeco.javacpp.opencv_imgproc._

    val imgSize: CvSize = cvGetSize(src)
    val dst = cvCreateImage(imgSize, src.depth(), 1)
    val storage = cvCreateMemStorage(0)

    val treshold = 40
    cvCanny(src, dst, treshold, treshold * 3, 3)

    val lines = cvHoughLines2(dst, storage, CV_HOUGH_PROBABILISTIC, 1, Math.PI / 180, 40, 35, jumpTreshold, 0, CV_PI)

    //println(lines.total())
    (0 until lines.total()).map { i =>
      val line = opencv_core.cvGetSeqElem(lines, i)
      val pt1: CvPoint = new CvPoint(line).position(0)
      val pt2 = new CvPoint(line).position(1)
      (pt1, pt2)
    }
  }

  def verticalBigLines(imgSize: CvSize)(line: Line) = {
    distance(line) > imgSize.height() / 2
  }

  def horizontalBigLines(imgSize: CvSize)(line: Line) = {
    distance(line) > imgSize.width() / 5 * 4
  }

  def filterByHeight(size: Int, error: Int)(lines: (Line, Line)) = {
    val pt1 = if (lines._1._1.x < lines._1._2.x) lines._1._1 else lines._1._2
    val pt2 = if (lines._2._1.x < lines._2._2.x) lines._2._1 else lines._2._2
    val dist = distance((pt1, pt2))
    dist > size - error && dist < size + error
  }

  def isHorizontal(error: Int)(line: Line) = {
    (line._1.y + error > line._2.y) && (line._1.y - error < line._2.y)
  }

  def isVertical(error: Int)(line: Line) = {
    (line._1.x + error > line._2.x) && (line._1.x - error < line._2.x)
  }

  def distance(line: Line) = {
    val x = line._1.x() - line._2.x()
    val y = line._1.y() - line._2.y()
    Math.sqrt(x * x + y * y)
  }

  def getInnerVerticalLines(lines: Seq[Line]): Option[(Line, Line)] = {
    if (lines.size % 2 != 0) {
      None
    } else {
      val (up, down) = lines.sortBy(_._1.x).splitAt(lines.size / 2)
      Some((up.last, down.head))
    }
  }

  def cropByVerticalLines(src: IplImage, lines: (Line, Line)) = {
    import org.bytedeco.javacpp.opencv_core.CvRect
    val rect = new CvRect
    rect.x(lines._1._1.x)
    rect.y(0)
    rect.width(lines._2._1.x - lines._1._1.x)
    rect.height(src.height())

    cvSetImageROI(src, rect)
    val cropped = cvCreateImage(cvGetSize(src), src.depth(), src.nChannels())
    cvCopy(src, cropped)
    cvResetImageROI(src)
    cropped
  }

  def pairHorizontalLines(lines: Seq[Line]) = {
    if (lines.isEmpty) {
      Seq.empty
    }
    else {
      val sorted = lines.sortBy(_._1.y())
      sorted.dropRight(1).zip(sorted.tail)
    }
  }

  def cropByHorizontalLines(src: IplImage, lines: (Line, Line)) = {
    import org.bytedeco.javacpp.opencv_core.CvRect
    val rect = new CvRect
    rect.x(0)
    rect.y(lines._1._1.y)
    rect.width(src.width())
    rect.height(lines._2._1.y - lines._1._1.y)

    cvSetImageROI(src, rect)
    val cropped = cvCreateImage(cvGetSize(src), src.depth(), src.nChannels())
    cvCopy(src, cropped)
    cvResetImageROI(src)
    cropped
  }

  def showImg(img: IplImage, title: String = ""): Unit = {
    val source = new CanvasFrame(title)
    val sourceConverter = new OpenCVFrameConverter.ToIplImage
    source.showImage(sourceConverter.convert(img))
    source.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE)
  }

  def iplImageToBufferedImage(source: IplImage) = {
    import java.awt.image.BufferedImage

    import org.bytedeco.javacv.{Java2DFrameConverter, OpenCVFrameConverter}

    val iplConverter = new OpenCVFrameConverter.ToIplImage
    val bimConverter = new Java2DFrameConverter
    val frame = iplConverter.convert(source)
    val img: BufferedImage = bimConverter.convert(frame)
    img
  }
}
