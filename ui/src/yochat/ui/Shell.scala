package yochat.ui

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.util.{Failure, Success}

import com.raquo.laminar.api.L.*

object Shell:

  def apply(api: ApiClient, ui: UiState): HtmlElement =
    div(
      cls <-- ui.mobileList.signal.map(value => if value then "shell list-open" else "shell chat-open"),
      div(cls := "drag-strip"),
      navTag(
        cls   := "rail",
        div(cls := "rail-brand", span(cls := "rail-mark", "有")),
        div(
          cls   := "rail-nav",
          railButton(
            "chat",
            "通信",
            ui.view.signal.map(_ == View.Chat),
            () => {
              ui.view.set(View.Chat)
              ui.mobileList.set(true)
            }
          ),
          railButton(
            "people",
            "联系人",
            ui.view.signal.map(_ == View.Contacts),
            () => {
              ui.view.set(View.Contacts)
              ui.mobileList.set(true)
            }
          ),
          child.maybe <-- api.state.signal.map { state =>
            if state.readOnly then None
            else
              Some(
                railButton(
                  "devices",
                  "设备",
                  ui.view.signal.map(_ == View.Devices),
                  () => {
                    ui.view.set(View.Devices)
                    ui.mobileList.set(true)
                  }
                )
              )
          }
        ),
        child.maybe <-- api.state.signal.map { state =>
          if state.readOnly then None
          else
            Some(
              div(
                cls := "rail-bottom",
                button(
                  cls        := "rail-button",
                  title      := "锁定本机",
                  aria.label := "锁定本机",
                  onClick --> (_ => api.lock()),
                  Icons("lock")
                )
              )
            )
        }
      ),
      conversationList(api, ui),
      mainTag(
        cls   := "workspace",
        child <-- ui.view.signal.map {
          case View.Chat     => ChatPanel(api, ui)
          case View.Contacts => ContactsPanel(api, ui)
          case View.Devices  => DevicesPanel(api, ui)
        }
      ),
      child.maybe <-- ui.addFriend.signal.map(value => if value then Some(AddFriendDialog(api, ui)) else None),
      child.maybe <-- ui.toast.signal.map(_.map(toast))
    )

  private def railButton(name: String, label: String, active: Signal[Boolean], action: () => Unit): HtmlElement =
    button(
      cls <-- active.map(value => if value then "rail-button on" else "rail-button"),
      title      := label,
      aria.label := label,
      onClick --> (_ => action()),
      Icons(name)
    )

  final private case class SearchState(
    query: String = "",
    results: SearchResults = SearchResults.empty,
    searching: Boolean = false
  )

  private def conversationList(api: ApiClient, ui: UiState): HtmlElement =
    val search = Var(SearchState())
    val token  = Var(0L)

    def runSearch(raw: String): Unit =
      val value = raw.trim
      val id    = token.now() + 1L
      token.set(id)
      if value.length < 2 then search.set(SearchState(query = raw))
      else
        search.set(SearchState(query = raw, searching = true))
        js.timers.setTimeout(200) {
          if token.now() == id then
            api.search(value).onComplete {
              case Success(found) =>
                if token.now() == id then search.set(SearchState(query = raw, results = found, searching = false))
              case Failure(fail)  =>
                if token.now() == id then
                  search.set(SearchState(query = raw))
                  ui.failure(api.describeError(fail))
            }
        }

    asideTag(
      cls := "conversation-list",
      div(
        cls := "list-head",
        div(cls       := "wordmark", "有聊", span("YOCHAT / S1")),
        div(
          cls         := "list-title-row",
          h2("通信"),
          child.maybe <-- api.state.signal.map(value =>
            if value.readOnly then None
            else
              Some(
                button(cls := "icon-button", aria.label := "添加联系人", title := "添加联系人", onClick --> (_ => ui.addFriend.set(true)), Icons("plus"))
              )
          )
        ),
        input(
          cls         := "text-input small search-input",
          aria.label  := "搜索本机历史",
          placeholder := "搜索本机历史（至少 2 个字）",
          value <-- search.signal.map(_.query),
          onInput.mapToValue --> (value => runSearch(value))
        )
      ),
      child <-- search.signal.map { current =>
        if current.query.trim.length < 2 then conversationRows(api, ui)
        else
          searchPanel(
            api,
            ui,
            current.query.trim,
            current.results,
            current.searching,
            () => search.set(SearchState())
          )
      },
      div(
        cls := "list-foot",
        span(child.text <-- api.state.signal.map(state => s"${state.contacts.size} 位联系人")),
        child.maybe <-- api.state.signal.map(state =>
          if state.readOnly then None else Some(button(cls := "text-button", onClick --> (_ => ui.addFriend.set(true)), "添加联系人"))
        )
      )
    )

  private def conversationRows(api: ApiClient, ui: UiState): HtmlElement =
    div(child <-- api.state.signal.map { state =>
      if state.conversations.isEmpty then emptyList(ui, state.readOnly)
      else
        div(
          cls := "conversation-stack",
          state.conversations.map { conversation =>
            val contact = state.contactFor(conversation)
            button(
              cls <-- api.activeConversation.signal.map(id =>
                if id.contains(conversation.id) then "conversation-row on" else "conversation-row"
              ),
              onClick --> (_ => {
                api.selectConversation(Some(conversation.id))
                ui.view.set(View.Chat)
                ui.mobileList.set(false)
              }),
              span(cls := "avatar", contact.map(_.displayName.take(1)).getOrElse("?")),
              span(
                cls    := "conversation-copy",
                span(cls := "conversation-name", conversation.peerName),
                span(cls := "conversation-preview", if conversation.lastMessage.isEmpty then "还没有消息" else conversation.lastMessage)
              ),
              span(cls := "conversation-time", formatTime(conversation.lastMessageAt))
            )
          }
        )
    })

  /** Search reads only decrypted snippets from the local bound (server caps the scan). The panel states how many rows were scanned so a
    * truncated scan is not presented as "no results".
    */
  private def searchPanel(
    api: ApiClient,
    ui: UiState,
    query: String,
    results: SearchResults,
    searching: Boolean,
    clear: () => Unit
  ): HtmlElement =
    div(
      cls := "search-results",
      if searching then div(cls := "search-empty", "正在搜索本机历史…")
      else if results.hits.isEmpty then div(cls := "search-empty", s"没有匹配「$query」的消息。")
      else
        div(
          cls                                   := "search-hits",
          results.hits.map { hit =>
            button(
              cls := "search-hit",
              onClick --> (_ => {
                api.selectConversation(Some(hit.conversationId))
                ui.view.set(View.Chat)
                ui.mobileList.set(false)
                clear()
              }),
              span(cls := "search-peer", hit.peerName),
              span(cls := "search-snippet", hit.snippet),
              span(cls := "search-time", formatTime(hit.createdAt))
            )
          }
        )
      ,
      if results.hits.nonEmpty then
        div(
          cls      := "search-note",
          s"扫描 ${results.scanned} 条" + (if results.truncated then "，只显示一部分匹配；换个词缩小范围。" else "")
        )
      else div(cls := "hidden", "")
    )

  private def emptyList(ui: UiState, readOnly: Boolean): HtmlElement =
    div(
      cls := "list-empty",
      div(cls := "empty-rule"),
      p(if readOnly then "只读设备已配对。" else "还没有联系人。"),
      span(if readOnly then "这里只显示主设备同步来的加入后内容。" else "用一份邀请开始一段只有你们两人的通信。"),
      if readOnly then div() else button(cls := "secondary-button", onClick --> (_ => ui.addFriend.set(true)), "添加联系人")
    )

  private def toast(value: Toast): HtmlElement =
    div(
      cls := s"toast ${value.kind}",
      span(cls := "toast-mark", if value.kind == "error" then "!" else "✓"),
      span(value.message)
    )

  private def formatTime(value: Long): String =
    if value <= 0L then ""
    else
      val date = new scalajs.js.Date(value.toDouble)
      val hh   = date.getHours().toInt
      val mm   = date.getMinutes().toInt
      s"${if hh < 10 then "0" else ""}$hh:${if mm < 10 then "0" else ""}$mm"
