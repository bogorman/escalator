package escalator.frontend.components.ui

import com.raquo.laminar.api.L._

object Icon {

  def apply(
      svgPaths: SvgElement*
  ): SvgElement = {
    svg.svg(
      svg.viewBox := "0 0 24 24",
      svg.fill := "none",
      svg.stroke := "currentColor",
      svg.strokeWidth := "1.5",
      svg.className := "size-6 shrink-0",
      svgPaths
    )
  }

  def withSize(
      size: String,
      svgPaths: SvgElement*
  ): SvgElement = {
    svg.svg(
      svg.viewBox := "0 0 24 24",
      svg.fill := "none",
      svg.stroke := "currentColor",
      svg.strokeWidth := "1.5",
      svg.className := s"$size shrink-0",
      svgPaths
    )
  }

  // Common icon paths from the reference
  def home(): SvgElement = {
    apply(
      svg.path(
        svg.d := "m2.25 12 8.954-8.955c.44-.439 1.152-.439 1.591 0L21.75 12M4.5 9.75v10.125c0 .621.504 1.125 1.125 1.125H9.75v-4.875c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125V21h4.125c.621 0 1.125-.504 1.125-1.125V9.75M8.25 21h8.25",
        svg.strokeLineCap := "round",
        svg.strokeLineJoin := "round"
      )
    )
  }

  def users(): SvgElement = {
    apply(
      svg.path(
        svg.d := "M15 19.128a9.38 9.38 0 0 0 2.625.372 9.337 9.337 0 0 0 4.121-.952 4.125 4.125 0 0 0-7.533-2.493M15 19.128v-.003c0-1.113-.285-2.16-.786-3.07M15 19.128v.106A12.318 12.318 0 0 1 8.624 21c-2.331 0-4.512-.645-6.374-1.766l-.001-.109a6.375 6.375 0 0 1 11.964-3.07M12 6.375a3.375 3.375 0 1 1-6.75 0 3.375 3.375 0 0 1 6.75 0Zm8.25 2.25a2.625 2.625 0 1 1-5.25 0 2.625 2.625 0 0 1 5.25 0Z",
        svg.strokeLineCap := "round",
        svg.strokeLineJoin := "round"
      )
    )
  }

  def folder(): SvgElement = {
    apply(
      svg.path(
        svg.d := "M2.25 12.75V12A2.25 2.25 0 0 1 4.5 9.75h15A2.25 2.25 0 0 1 21.75 12v.75m-8.69-6.44-2.12-2.12a1.5 1.5 0 0 0-1.061-.44H4.5A2.25 2.25 0 0 0 2.25 6v12a2.25 2.25 0 0 0 2.25 2.25h15A2.25 2.25 0 0 0 21.75 18V9a2.25 2.25 0 0 0-2.25-2.25h-5.379a1.5 1.5 0 0 1-1.06-.44Z",
        svg.strokeLineCap := "round",
        svg.strokeLineJoin := "round"
      )
    )
  }

  def calendar(): SvgElement = {
    apply(
      svg.path(
        svg.d := "M6.75 3v2.25M17.25 3v2.25M3 18.75V7.5a2.25 2.25 0 0 1 2.25-2.25h13.5A2.25 2.25 0 0 1 21 7.5v11.25m-18 0A2.25 2.25 0 0 0 5.25 21h13.5A2.25 2.25 0 0 0 21 18.75m-18 0v-7.5A2.25 2.25 0 0 1 5.25 9h13.5A2.25 2.25 0 0 1 21 11.25v7.5",
        svg.strokeLineCap := "round",
        svg.strokeLineJoin := "round"
      )
    )
  }

  def document(): SvgElement = {
    apply(
      svg.path(
        svg.d := "M15.75 17.25v3.375c0 .621-.504 1.125-1.125 1.125h-9.75a1.125 1.125 0 0 1-1.125-1.125V7.875c0-.621.504-1.125 1.125-1.125H6.75a9.06 9.06 0 0 1 1.5.124m7.5 10.376h3.375c.621 0 1.125-.504 1.125-1.125V11.25c0-4.46-3.243-8.161-7.5-8.876a9.06 9.06 0 0 0-1.5-.124H9.375c-.621 0-1.125.504-1.125 1.125v3.5m7.5 10.375H9.375a1.125 1.125 0 0 1-1.125-1.125v-9.25m12 6.625v-1.875a3.375 3.375 0 0 0-3.375-3.375h-1.5a1.125 1.125 0 0 1-1.125-1.125v-1.5a3.375 3.375 0 0 0-3.375-3.375H9.75",
        svg.strokeLineCap := "round",
        svg.strokeLineJoin := "round"
      )
    )
  }

  def chart(): SvgElement = {
    apply(
      svg.path(
        svg.d := "M10.5 6a7.5 7.5 0 1 0 7.5 7.5h-7.5V6Z",
        svg.strokeLineCap := "round",
        svg.strokeLineJoin := "round"
      ),
      svg.path(
        svg.d := "M13.5 10.5H21A7.5 7.5 0 0 0 13.5 3v7.5Z",
        svg.strokeLineCap := "round",
        svg.strokeLineJoin := "round"
      )
    )
  }

  def settings(): SvgElement = {
    apply(
      svg.path(
        svg.d := "M9.594 3.94c.09-.542.56-.94 1.11-.94h2.593c.55 0 1.02.398 1.11.94l.213 1.281c.063.374.313.686.645.87.074.04.147.083.22.127.325.196.72.257 1.075.124l1.217-.456a1.125 1.125 0 0 1 1.37.49l1.296 2.247a1.125 1.125 0 0 1-.26 1.431l-1.003.827c-.293.241-.438.613-.43.992a7.723 7.723 0 0 1 0 .255c-.008.378.137.75.43.991l1.004.827c.424.35.534.955.26 1.43l-1.298 2.247a1.125 1.125 0 0 1-1.369.491l-1.217-.456c-.355-.133-.75-.072-1.076.124a6.47 6.47 0 0 1-.22.128c-.331.183-.581.495-.644.869l-.213 1.281c-.09.543-.56.94-1.11.94h-2.594c-.55 0-1.019-.398-1.11-.94l-.213-1.281c-.062-.374-.312-.686-.644-.87a6.52 6.52 0 0 1-.22-.127c-.325-.196-.72-.257-1.076-.124l-1.217.456a1.125 1.125 0 0 1-1.369-.49l-1.297-2.247a1.125 1.125 0 0 1 .26-1.431l1.004-.827c.292-.24.437-.613.43-.991a6.932 6.932 0 0 1 0-.255c.007-.38-.138-.751-.43-.992l-1.004-.827a1.125 1.125 0 0 1-.26-1.43l1.297-2.247a1.125 1.125 0 0 1 1.37-.491l1.216.456c.356.133.751.072 1.076-.124.072-.044.146-.086.22-.128.332-.183.582-.495.644-.869l.214-1.28Z",
        svg.strokeLineCap := "round",
        svg.strokeLineJoin := "round"
      ),
      svg.path(
        svg.d := "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z",
        svg.strokeLineCap := "round",
        svg.strokeLineJoin := "round"
      )
    )
  }

  def bars3(): SvgElement = {
    apply(
      svg.path(
        svg.d := "M3.75 6.75h16.5M3.75 12h16.5m-16.5 5.25h16.5",
        svg.strokeLineCap := "round",
        svg.strokeLineJoin := "round"
      )
    )
  }

  def magnifyingGlass(): SvgElement = {
    svg.svg(
      svg.viewBox := "0 0 20 20",
      svg.fill := "currentColor",
      svg.className := "pointer-events-none col-start-1 row-start-1 size-5 self-center text-gray-400",
      svg.path(
        svg.d := "M9 3.5a5.5 5.5 0 1 0 0 11 5.5 5.5 0 0 0 0-11ZM2 9a7 7 0 1 1 12.452 4.391l3.328 3.329a.75.75 0 1 1-1.06 1.06l-3.329-3.328A7 7 0 0 1 2 9Z",
        svg.clipRule := "evenodd",
        svg.fillRule := "evenodd"
      )
    )
  }

  def bell(): SvgElement = {
    apply(
      svg.path(
        svg.d := "M14.857 17.082a23.848 23.848 0 0 0 5.454-1.31A8.967 8.967 0 0 1 18 9.75V9A6 6 0 0 0 6 9v.75a8.967 8.967 0 0 1-2.312 6.022c1.733.64 3.56 1.085 5.455 1.31m5.714 0a24.255 24.255 0 0 1-5.714 0m5.714 0a3 3 0 1 1-5.714 0",
        svg.strokeLineCap := "round",
        svg.strokeLineJoin := "round"
      )
    )
  }

  def chevronDown(): SvgElement = {
    svg.svg(
      svg.viewBox := "0 0 20 20",
      svg.fill := "currentColor",
      svg.className := "ml-2 size-5 text-gray-400 dark:text-gray-500",
      svg.path(
        svg.d := "M5.22 8.22a.75.75 0 0 1 1.06 0L10 11.94l3.72-3.72a.75.75 0 1 1 1.06 1.06l-4.25 4.25a.75.75 0 0 1-1.06 0L5.22 9.28a.75.75 0 0 1 0-1.06Z",
        svg.clipRule := "evenodd",
        svg.fillRule := "evenodd"
      )
    )
  }

  def close(): SvgElement = {
    svg.svg(
      svg.viewBox := "0 0 20 20",
      svg.fill := "currentColor",
      svg.className := "h-5 w-5",
      svg.path(
        svg.d := "M6.28 5.22a.75.75 0 0 0-1.06 1.06L8.94 10l-3.72 3.72a.75.75 0 1 0 1.06 1.06L10 11.06l3.72 3.72a.75.75 0 1 0 1.06-1.06L11.06 10l3.72-3.72a.75.75 0 0 0-1.06-1.06L10 8.94 6.28 5.22Z",
        svg.clipRule := "evenodd",
        svg.fillRule := "evenodd"
      )
    )
  }
}
