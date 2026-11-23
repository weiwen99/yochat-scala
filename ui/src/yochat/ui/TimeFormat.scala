package yochat.ui

import scala.scalajs.js

object TimeFormat:

  def clock(value: Long): String =
    if value <= 0L then ""
    else
      val date = new js.Date(value.toDouble)
      val hour = date.getHours().toInt
      val min  = date.getMinutes().toInt
      s"${if hour < 10 then "0" else ""}$hour:${if min < 10 then "0" else ""}$min"

  def day(value: Long): String =
    if value <= 0L then ""
    else
      val date = new js.Date(value.toDouble)
      val now  = new js.Date()
      val same =
        date.getFullYear() == now.getFullYear() &&
          date.getMonth() == now.getMonth() &&
          date.getDate() == now.getDate()
      if same then "今天"
      else s"${date.getMonth().toInt + 1} 月 ${date.getDate().toInt} 日"

  def timestamp(value: Long): String =
    if value <= 0L then ""
    else
      val date = new js.Date(value.toDouble)
      s"${date.getMonth().toInt + 1}/${date.getDate().toInt} ${clock(value)}"
