package lk.wms.common.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Sends one plain-text email (v2.0 sign-up). Inside a transaction it waits until the commit, so a rolled-back sign-up
 * never mails a link. With no mail server configured it only logs that nothing was sent — never the body, which can
 * hold a one-time link (SEC-25 spirit: secrets stay out of logs).
 */
@Component
public class Mailer {

    private static final Logger log = LoggerFactory.getLogger(Mailer.class);

    private final ObjectProvider<JavaMailSender> sender;
    private final String from;

    public Mailer(ObjectProvider<JavaMailSender> sender, @Value("${wms.mail.from}") String from) {
        this.sender = sender;
        this.from = from;
    }

    public void send(String to, String subject, String text) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deliver(to, subject, text);
                }
            });
        } else {
            deliver(to, subject, text);
        }
    }

    private void deliver(String to, String subject, String text) {
        JavaMailSender mail = sender.getIfAvailable();
        if (mail == null) {
            log.warn("Email not sent (mail not configured): \"{}\"", subject);
            return;
        }
        try {
            MimeMessage message = mail.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, false);
            mail.send(message);
        } catch (MessagingException | MailException e) {
            log.warn("Email \"{}\" could not be sent: {}", subject, e.getMessage()); // the person can sign up again
        }
    }
}
