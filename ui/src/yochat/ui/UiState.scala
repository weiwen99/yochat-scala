package yochat.ui

import scala.scalajs.js

import com.raquo.laminar.api.L.*

enum View:
  case Chat, Contacts

enum InviteTab:
  case Issued, Received

final case class Toast(message: String, kind: String)

final class UiState:

  val view: Var[View]            = Var(View.Chat)
  val addFriend: Var[Boolean]    = Var(false)
  val inviteTab: Var[InviteTab]  = Var(InviteTab.Issued)
  val mobileList: Var[Boolean]   = Var(true)
  val toast: Var[Option[Toast]]  = Var(None)
  val aliasEditing: Var[Boolean] = Var(false)
  val aliasDraft: Var[String]    = Var("")
  val inviteText: Var[String]    = Var("")
  val replyText: Var[String]     = Var("")

  def notify(message: String, kind: String = "info"): Unit =
    toast.set(Some(Toast(message, kind)))
    js.timers.setTimeout(4200) {
      if toast.now().exists(_.message == message) then toast.set(None)
    }

  def failure(message: String): Unit = notify(message, "error")
