package yochat.ui

import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.StringAsIsCodec
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.scalajs.dom

object Icons:

  def apply(name: String): ReactiveSvgElement[dom.SVGSVGElement] =
    svg.svg(
      svg.svgAttr("class", StringAsIsCodec, namespace = None)       := "icon",
      svg.viewBox                                                   := "0 0 24 24",
      svg.fill                                                      := "none",
      svg.stroke                                                    := "currentColor",
      svg.strokeWidth                                               := "1.6",
      svg.strokeLineCap                                             := "round",
      svg.strokeLineJoin                                            := "round",
      svg.svgAttr("aria-hidden", StringAsIsCodec, namespace = None) := "true",
      svg.path(svg.d := path(name))
    )

  private def path(name: String): String =
    name match
      case "chat"    => "M4 5h16v11H9l-5 4z"
      case "people"  => "M9 8a3 3 0 1 0 0 6 3 3 0 0 0 0-6M3 20c0-3.3 2.7-6 6-6s6 2.7 6 6M17 8h4M19 6v4"
      case "devices" => "M4 5h16v10H4zM8 19h8M12 15v4"
      case "lock"    => "M5 10h14v10H5zM8 10V7a4 4 0 0 1 8 0v3"
      case "shield"  => "M12 3l7 3v5c0 5-3 8-7 10-4-2-7-5-7-10V6zM8.5 12l2.3 2.3L16 9.3"
      case "send"    => "M5 12h13M13 6l6 6-6 6"
      case "copy"    => "M8 8h10v12H8zM6 16H4V4h10v2"
      case "check"   => "M5 12l4 4L19 6"
      case "plus"    => "M12 5v14M5 12h14"
      case "close"   => "M6 6l12 12M18 6L6 18"
      case "back"    => "M19 12H5M11 6l-6 6 6 6"
      case "edit"    => "M4 20h4L19 9l-4-4L4 16zM13 7l4 4"
      case "route"   => "M5 6h4v4H5zM15 14h4v4h-4zM9 8h4a2 2 0 0 1 2 2v4"
      case "arrow"   => "M5 12h14M13 6l6 6-6 6"
      case _         => "M5 12h14"
