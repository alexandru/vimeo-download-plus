/*
 * Copyright (c) 2018 Alexandru Nedelcu.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.alexn.vdp

import java.net.URLEncoder
import io.circe.Printer
import io.circe.syntax._
import monix.eval.Task
import org.alexn.vdp.models.{
  DownloadLinksJSON,
  HttpError,
  JSONError,
  VideoConfigJSON,
  WebError
}
import org.http4s.circe.{CirceInstances, _}
import org.http4s.dsl.Http4sDsl
import org.http4s.util.CaseInsensitiveString
import org.http4s.{Header, HttpRoutes, Request, Response, Status}
import org.slf4j.LoggerFactory
import scala.concurrent.duration._

final class Controller private (vimeo: VimeoClient) extends Http4sDsl[Task] {

  lazy val routes = HttpRoutes.of[Task] {
    case GET -> Root =>
      Ok("Pong")

    case request @ GET -> Root / "redirect" / uid / name :? DownloadParam(
          download
        ) =>
      findDownloads(request, uid, 1.minute) { info =>
        if (info.allowDownloads) {
          val search = name.toLowerCase.trim
          val file = info.sourceFile
            .find(_.publicName.toLowerCase.trim == search)
            .orElse(info.files.find(_.publicName.toLowerCase.trim == search))

          file match {
            case None =>
              notFound(s"file with name $name not found")

            case Some(value) =>
              val url = cleanURL(value.downloadURL, download.getOrElse(true))
              logger.info("Serving (video): " + url)
              Response[Task](Status.SeeOther)
                .putHeaders(Header("Location", url))
                .putHeaders(Header("Cache-Control", "no-cache"))
          }
        } else {
          notFound("allowDownloads=false")
        }
      }

    case request @ GET -> Root / "get" / uid =>
      findDownloads(request, uid, 24.hours) { info =>
        /*_*/

        Response[Task](Status.Ok)
          .withEntity(info.asJson)
          .putHeaders(Header("Cache-Control", "max-age=86400"))
        /*_*/
      }

    case request @ GET -> Root / "config" / uid =>
      findThumb(request, uid, 1.hour) { info =>
        /*_*/

        Response[Task](Status.Ok)
          .withEntity(info.asJson)
          .putHeaders(Header("Cache-Control", "max-age=3600"))
        /*_*/
      }

    case request @ GET -> Root / "thumb" / uid =>
      findThumb(request, uid, 24.hours) { info =>
        val image = info.video.thumbs.image1280
          .orElse(info.video.thumbs.base)

        image match {
          case None => notFound("video.thumbs.base")
          case Some(url) =>
            logger.info("Serving (thumb): " + url)
            Response[Task](Status.SeeOther)
              .putHeaders(Header("Location", url))
              .putHeaders(Header("Cache-Control", "max-age=3600"))
        }
      }

    case request @ GET -> Root / "thumb-play" / uid =>
      findThumb(request, uid, 24.hours) { info =>
        val image = info.video.thumbs.image1280
          .orElse(info.video.thumbs.base)

        image match {
          case None => notFound("video.thumbs.base")
          case Some(url) =>
            val urlPlay = "https://i.vimeocdn.com/filter/overlay?src0=" +
              URLEncoder.encode(url, "utf-8") +
              "&src1=https%3A%2F%2Ff.vimeocdn.com%2Fimages_v6%2Fshare%2Fplay_icon_overlay.png"

            logger.info("Serving (thumb-play): " + urlPlay)
            Response[Task](Status.SeeOther)
              .putHeaders(Header("Location", urlPlay))
              .putHeaders(Header("Cache-Control", "max-age=3600"))
        }
      }
  }

  private def findThumb(
    request: Request[Task],
    uid: String,
    exp: FiniteDuration
  )(f: VideoConfigJSON => Response[Task]): Task[Response[Task]] = {

    find(request, f) { (agent, forwardedFor) =>
      vimeo.getConfig(uid, exp, agent, forwardedFor)
    }
  }

  private def findDownloads(
    request: Request[Task],
    uid: String,
    exp: FiniteDuration
  )(f: DownloadLinksJSON => Response[Task]) = {

    find(request, f) { (agent, forwardedFor) =>
      vimeo.getDownloadLinks(uid, exp, agent, forwardedFor)
    }
  }

  type UserAgent = Header
  type ForwardedFor = Header

  private def find[T](request: Request[Task], f: T => Response[Task])(
    generate: (Option[UserAgent],
               Option[ForwardedFor]) => Task[Either[WebError, T]]
  ) = {

    System.realIP.flatMap { serverIP =>
      val agent = request.headers.get(CaseInsensitiveString("User-Agent"))
      val currentForwardedFor =
        request.headers
          .get(CaseInsensitiveString("X-Forwarded-For"))
          .orElse(request.headers.get(CaseInsensitiveString("X-Client-IP")))
          .orElse(request.headers.get(CaseInsensitiveString("X-ProxyUser-Ip")))

      val forwardedFor = serverIP.filter(_.nonEmpty) match {
        case None => currentForwardedFor
        case Some(proxyIP) =>
          currentForwardedFor match {
            case None =>
              request.remoteAddr.map(
                ip => Header("X-Forwarded-For", ip + ", " + proxyIP)
              )
            case Some(header) =>
              Some(Header("X-Forwarded-For", header.value + ", " + proxyIP))
          }
      }

      generate(agent, forwardedFor).map {
        case Left(HttpError(status, body, contentType)) =>
          // Core web-service triggered an HTTP error, mirroring it as is
          val r =
            Response[Task](Status.fromInt(status).right.get).withEntity(body)
          contentType.fold(r)(ct => r.putHeaders(Header("Content-Type", ct)))

        case Left(JSONError(msg)) =>
          // Exceptions was thrown somewhere, not good
          logger.warn("Core web service problem — {}", msg)
          Response[Task](Status.BadGateway)
            .withEntity("502 Bad Gateway (Vimeo)")

        case Left(error) =>
          // Exceptions was thrown somewhere, not good
          logger.warn("Unexpected error: {}", error.toString)
          Response[Task](Status.InternalServerError)
            .withEntity("500 Internal Server Error")

        case Right(info) =>
          f(info)
      }
    }
  }

  private def cleanURL(url: String, download: Boolean): String =
    if (download) {
      if (!url.contains("download"))
        url + "&download=1"
      else
        url
    } else {
      url.replaceAll("[&]download[=]\\w+", "")
    }

  private object DownloadParam
      extends OptionalQueryParamDecoderMatcher[Boolean]("download")

  private[this] def notFound(reason: String) = {
    logger.warn(s"Not Found: $reason")
    Response[Task](Status.NotFound).withEntity("404 Not Found")
  }

  // For JSON encoding
  private lazy val circe =
    CirceInstances.withPrinter(Printer.spaces2)

  private[this] val logger =
    LoggerFactory.getLogger(getClass)
}

object Controller {

  /** Builds a [[Controller]]. */
  def apply(vimeo: VimeoClient): Task[Controller] =
    Task(new Controller(vimeo))
}
