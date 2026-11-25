package com.fuelplatform.simulator;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * Classifies a transport failure by what it says about the simulator.
 *
 * <p>The distinction matters, because the two failures call for opposite
 * responses:
 *
 * <table border="1">
 *   <caption>Transport failures</caption>
 *   <tr><th>Failure</th><th>What it means</th><th>Should the breaker count it?</th></tr>
 *   <tr>
 *     <td><strong>Timeout</strong></td>
 *     <td>The simulator is <em>slow</em>. It may well be alive and working.</td>
 *     <td><strong>No.</strong> A slow simulator is not a dead one.</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Connection refused / reset</strong></td>
 *     <td>The simulator is <em>not listening</em>. It is down or restarting.</td>
 *     <td><strong>Yes.</strong> This is exactly what the breaker is for.</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Connect timeout</strong></td>
 *     <td>The simulator never accepted the connection. Nothing is answering.</td>
 *     <td><strong>Yes.</strong> A connect timeout is an unreachable host, not a slow one.</td>
 *   </tr>
 * </table>
 *
 * <p><strong>Why a timeout must not open the breaker.</strong> Opening it
 * refunds the timeout but takes the whole integration offline for the open
 * window. If the simulator is merely slow, that is a strictly worse outcome:
 * the platform stops reading a simulator that would have answered given a
 * little more time, and every dependent feature — the shipment tracker, the
 * risk engine — goes dark with it.
 *
 * <p>This was a real regression, found by running the Day 3 end-to-end
 * verifier against the Day 4 code. The verifier injects a `latency` fault, and
 * the resulting timeouts opened the breaker for 30 seconds, which starved the
 * shipment tracker of the reads it needs. Two Day 3 checks failed as a
 * result. The fix is this class.
 *
 * <p><strong>The exception shapes are measured, not assumed.</strong> The raw
 * JDK client and Spring's {@code RestClient} wrap a failure differently, so
 * {@code SimulatorTransportTimeoutTest} drives both real cases through the
 * real transport and prints the chains:
 *
 * <pre>
 *   read timeout      ResourceAccessException -> HttpTimeoutException
 *   connection refused ResourceAccessException -> ConnectException -> ClosedChannelException
 * </pre>
 *
 * <p>Guessing at those chains is how the timeout regression happened in the
 * first place, so the measurement is a test rather than a comment.
 */
final class TransportFailureClassifier {

    private TransportFailureClassifier() {
    }

    /**
     * Whether a transport failure proves the simulator is unavailable.
     *
     * <p>Only a connection-level failure does. A timeout proves the opposite:
     * something answered, just not in time.
     */
    static boolean isUnavailable(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 10) {
            // A connect timeout means the connection was never established.
            // Nothing answered at all, which is an outage rather than slowness.
            //
            // This is checked BEFORE the read-timeout branch on purpose:
            // HttpConnectTimeoutException extends HttpTimeoutException, so the
            // order decides the outcome. Getting it backwards would classify a
            // blackholed simulator as "slow" and the breaker would never open
            // for the one failure that really is an outage.
            if (current instanceof HttpConnectTimeoutException) {
                return true;
            }
            if (current instanceof ConnectException
                    || current instanceof java.net.NoRouteToHostException
                    || current instanceof java.net.UnknownHostException
                    || current instanceof java.net.PortUnreachableException) {
                return true;
            }
            // A cancelled request is how the JDK client reports a read timeout.
            if (current instanceof HttpTimeoutException
                    || current instanceof TimeoutException
                    || current instanceof InterruptedIOException) {
                return false;
            }
            // Spring wraps the client's failure in a ResourceAccessException whose
            // message is the JDK's own. When the cause chain is empty, the
            // message is the only signal left, so it is checked too. "Request
            // cancelled" is the JDK's wording for a read timeout.
            if (current.getCause() == null && isTimeoutMessage(current.getMessage())) {
                return false;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    /**
     * Whether a message describes a timeout.
     *
     * <p>Only consulted when the cause chain has run out, so it is a last
     * resort rather than the primary signal. The wording comes from the JDK
     * client, which reports a read timeout as "Request cancelled".
     */
    private static boolean isTimeoutMessage(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("request cancelled")
                || lower.contains("timed out")
                || lower.contains("timeout");
    }
}
