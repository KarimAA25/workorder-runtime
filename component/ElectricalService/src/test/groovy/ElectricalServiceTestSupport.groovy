/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */
import org.moqui.context.ExecutionContext

/** Shared constants/fixtures for ElectricalService Spock specs. Users referenced here come
 * from data/ElectricalServiceDemoData.xml (type="demo"), loaded with
 * "gradlew cleanAll load -Ptypes=seed,demo". */
class ElectricalServiceTestSupport {
    static final String DISPATCHER_USERNAME = "es.dispatcher"
    static final String DISPATCHER_PASSWORD = "moqui"
    static final String DISPATCHER_PARTY_ID = "ES_DISPATCH1"

    static final String TECH1_USERNAME = "es.tech1"
    static final String TECH1_PASSWORD = "moqui"
    static final String TECH1_PARTY_ID = "ES_TECH1"

    static final String TECH2_USERNAME = "es.tech2"
    static final String TECH2_PASSWORD = "moqui"
    static final String TECH2_PARTY_ID = "ES_TECH2"

    static final String CUSTOMER1_USERNAME = "es.customer1"
    static final String CUSTOMER1_PASSWORD = "moqui"
    static final String CUSTOMER1_PARTY_ID = "ES_CUST1"

    static void login(ExecutionContext ec, String username, String password) {
        logout(ec)
        ec.user.loginUser(username, password)
    }
    static void logout(ExecutionContext ec) {
        while (ec.user.userId != null) ec.user.logoutUser()
    }

    static void withAuthzDisabled(ExecutionContext ec, Closure c) {
        boolean already = ec.artifactExecution.disableAuthz()
        try { c.call() } finally { if (!already) ec.artifactExecution.enableAuthz() }
    }
}
