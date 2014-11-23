package com.twitter.finagle

import com.twitter.finagle.client.{Bridge, DefaultClient}
import com.twitter.finagle.smtp._
import com.twitter.finagle.smtp.extension._
import com.twitter.finagle.smtp.filter._
import com.twitter.finagle.smtp.transport.SmtpTransporter
import com.twitter.logging.Logger

trait SmtpRichClient { self: Client[Request, Reply] =>

  /**
   * Constructs an SMTP client which can support
   * given extensions, if they are supported by server.
   *
   * @param extensions Extensions to support
   */
  def withSupportFor(extensions: Extensions): Client[Request, Reply]

  /**
   * Constructs a new client that can send emails.
   */
  def newSimpleService(dest: String): Service[EmailMessage, Unit]
}

/**
 * Creates different types of clients
 */
object Smtp extends Client[Request, Reply] with SmtpRichClient {


  /**
   * Implements an SMTP client with given extensions
   * that greets server upon connection.
   */
  case class SmtpClient(canSupport: Extensions = Extensions()) extends Client[Request, Reply] {
    // TODO: switch to StackClient
    private val defaultClient = DefaultClient[Request, Reply] (
      name = "smtp",
      endpointer = {
        val bridge = Bridge[Request, UnspecifiedReply, Request, Reply](
          SmtpTransporter, new SmtpClientDispatcher(_)
        )
        (addr, stats) => bridge(addr, stats)
      })

    def newClient(dest: Name, label: String) = {
      new ServiceFactoryProxy[Request, Reply](defaultClient.newClient(dest, label)){
        override def apply(conn: ClientConnection) = {
          self.apply(conn) flatMap { service =>
            // Send EHLO and get extensions supported by server
            Esmtp.greet(service, canSupport) map { extensions =>
              // Shield message body
              DataFilter andThen
                // Transform OK replies to Extensions replies
                OkToExtFilter andThen
                // Make behaviour changes according to supported extensions
                Esmtp(extensions).extend(service)
            }}}}
    }
  }

  override def newClient(dest: Name, label: String) = SmtpClient().newClient(dest, label)

  def withSupportFor(extensions: Extensions) = SmtpClient(extensions)

  def newSimpleService(dest: String) = SmtpSimple.newService(dest)
}

/**
 * Implements an SMTP client that can send an [[com.twitter.finagle.smtp.EmailMessage]].
 * The application of this client's service returns [[com.twitter.util.Future.Done]]
 * in case of success or the first encountered error in case of a failure.
 */
object SmtpSimple extends Client[EmailMessage, Unit] {
  private def mkSimple(underlying: ServiceFactory[Request, Reply])
    :ServiceFactory[EmailMessage, Unit] = {
      HeadersFilter andThen MailFilter andThen DefaultTimeoutsFilter andThen underlying
  }

  override def newClient(dest: Name, label: String) = {
    mkSimple(Smtp.newClient(dest, label))
  }

  /**
   * Provides SMTP client that sends emails
   * and logs the session.
   *
   * @param log The logger to use
   */
  def withLogging(log: Logger) = new Client[EmailMessage, Unit] {
    def newClient(dest: Name, label: String) = {
      mkSimple(new SmtpLoggingFilter(log) andThen Smtp.newClient(dest, label))
    }
  }
}
