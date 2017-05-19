package org.bigbluebutton.client.meeting

import akka.actor.{Actor, ActorLogging, Props}
import org.bigbluebutton.client.SystemConfiguration
import org.bigbluebutton.client.bus._
import org.bigbluebutton.common2.messages.{BbbCommonEnvJsNodeMsg, BbbCoreEnvelope, BbbCoreHeaderBody}
import org.bigbluebutton.common2.util.JsonUtil

object UserActor {
  def props(userId: String,
            msgToAkkaAppsEventBus: MsgToAkkaAppsEventBus,
            meetingId: String,
            msgToClientEventBus: MsgToClientEventBus): Props =
    Props(classOf[UserActor], userId, msgToAkkaAppsEventBus, meetingId, msgToClientEventBus)
}

class UserActor(val userId: String,
                msgToAkkaAppsEventBus: MsgToAkkaAppsEventBus,
                meetingId: String,
                msgToClientEventBus: MsgToClientEventBus)
  extends Actor with ActorLogging with SystemConfiguration {

  private val conns = new Connections

  def receive = {
    case msg: ConnectMsg => handleConnectMsg(msg)
    case msg: DisconnectMsg => handleDisconnectMsg(msg)
    case msg: MsgFromClientMsg => handleMsgFromClientMsg(msg)
    case msg: BbbCommonEnvJsNodeMsg => handleBbbServerMsg(msg)
  }

  private def createConnection(id: String, sessionId: String, active: Boolean): Connection = {
    Connection(id, sessionId, active)
  }

  def handleConnectMsg(msg: ConnectMsg): Unit = {
    log.debug("Received ConnectMsg " + msg)
    Connections.findWithId(conns, msg.connInfo.connId) match {
      case Some(m) => log.warning("Connect message on same connection id. " + JsonUtil.toJson(msg.connInfo))
      case None =>
        for {
          activeConn <- Connections.findActiveConnection(conns)
        } yield {
          Connections.setConnInactive(conns, activeConn)
        }
        val m = createConnection(msg.connInfo.connId, msg.connInfo.sessionId, true)
        Connections.add(conns, m)
    }
  }

  def handleDisconnectMsg(msg: DisconnectMsg): Unit = {
    log.debug("Received DisconnectMsg " + msg)
    for {
      m <- Connections.findWithId(conns, msg.connInfo.connId)
    } yield {
      Connections.remove(conns, m.connId)
    }
  }

  def handleMsgFromClientMsg(msg: MsgFromClientMsg):Unit = {
    log.debug("Received MsgFromClientMsg " + msg)

    val headerAndBody = JsonUtil.fromJson[BbbCoreHeaderBody](msg.json)
    val meta = collection.immutable.HashMap[String, String](
      "meetingId" -> msg.connInfo.meetingId,
      "userId" -> msg.connInfo.userId
    )

    val envelope = new BbbCoreEnvelope(headerAndBody.header.name, meta)
    val akkaMsg = BbbCommonEnvJsNodeMsg(envelope, JsonUtil.toJsonNode(msg.json))
    msgToAkkaAppsEventBus.publish(MsgToAkkaApps(toAkkaAppsChannel, akkaMsg))
  }

  def handleBbbServerMsg(msg: BbbCommonEnvJsNodeMsg): Unit = {
    log.debug("Received BbbServerMsg " + msg)
    for {
      msgType <- msg.envelope.routing.get("msgType")
    } yield {
      handleServerMsg(msgType, msg)
    }
  }

  def handleServerMsg(msgType: String, msg: BbbCommonEnvJsNodeMsg): Unit = {
    msgType match {
      case "direct" => handleDirectMessage(msg)
      case "broadcast" => handleBroadcastMessage(msg)
      case "system" => handleSystemMessage(msg)
    }
  }

  private def forwardToUser(msg: BbbCommonEnvJsNodeMsg): Unit = {
    for {
      conn <- Connections.findActiveConnection(conns)
    } yield {
      msgToClientEventBus.publish(MsgToClientBusMsg(toClientChannel, DirectMsgToClient(meetingId, conn.connId, msg)))
    }
  }

  def handleDirectMessage(msg: BbbCommonEnvJsNodeMsg): Unit = {
    // In case we want to handle specific messages. We can do it here.
    forwardToUser(msg)
  }

  def handleBroadcastMessage(msg: BbbCommonEnvJsNodeMsg): Unit = {
    // In case we want to handle specific messages. We can do it here.
    forwardToUser(msg)
  }

  def handleSystemMessage(msg: BbbCommonEnvJsNodeMsg): Unit = {
    for {
      conn <- Connections.findActiveConnection(conns)
    } yield {
      val json = JsonUtil.toJson(msg.jsonNode)
      msgToClientEventBus.publish(MsgToClientBusMsg(toClientChannel, SystemMsgToClient(meetingId, conn.connId, msg)))
    }
  }
}

