/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import spock.lang.Shared
import spock.lang.Specification

/* Run with (from repo root): gradlew.bat cleanAll load -Ptypes=seed,demo
    then: gradlew.bat :runtime:component:ElectricalService:test
   Covers record-level ("only my records") filtering via EntityFilterSet, not just the
   service-level authz already proven by ElectricalServiceWorkflowTests.
 */
class ElectricalServiceSecurityTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String serviceRequestId
    @Shared String serviceVisitId

    def setupSpec() {
        ec = Moqui.getExecutionContext()

        // Set up one Request assigned to and scheduled for Technician 1, via the Dispatcher,
        // the same way ElectricalServiceWorkflowTests does (kept independent of that spec's
        // fixtures so this spec can run alone).
        ElectricalServiceTestSupport.login(ec, ElectricalServiceTestSupport.DISPATCHER_USERNAME, ElectricalServiceTestSupport.DISPATCHER_PASSWORD)
        Map createOut = ec.service.sync().name("ElectricalService.ElectricalServiceServices.create#ServiceRequest")
                .parameters([customerPartyId: ElectricalServiceTestSupport.CUSTOMER1_PARTY_ID, description: "Security test fixture", priority: 5]).call()
        serviceRequestId = createOut.serviceRequestId
        ec.service.sync().name("ElectricalService.ElectricalServiceServices.assign#Technician")
                .parameters([serviceRequestId: serviceRequestId, technicianPartyId: ElectricalServiceTestSupport.TECH1_PARTY_ID]).call()
        Map scheduleOut = ec.service.sync().name("ElectricalService.ElectricalServiceServices.schedule#Visit")
                .parameters([serviceRequestId: serviceRequestId, scheduledDate: ec.user.nowTimestamp]).call()
        serviceVisitId = scheduleOut.serviceVisitId
        ElectricalServiceTestSupport.logout(ec)
    }
    def cleanupSpec() { ec.destroy() }

    def cleanup() {
        ec.message.clearAll()
        ElectricalServiceTestSupport.logout(ec)
    }

    static EntityValue findWorkEffort(ExecutionContext ec, String workEffortId) {
        EntityValue ev = null
        ElectricalServiceTestSupport.withAuthzDisabled(ec) {
            ev = ec.entity.find("mantle.work.effort.WorkEffort").condition("workEffortId", workEffortId).one()
        }
        return ev
    }

    def "Technician 1 (assigned) sees the Service Request via find"() {
        when:
        ElectricalServiceTestSupport.login(ec, ElectricalServiceTestSupport.TECH1_USERNAME, ElectricalServiceTestSupport.TECH1_PASSWORD)
        Map result = ec.service.sync().name("ElectricalService.ElectricalServiceServices.find#ServiceRequests").parameters([:]).call()

        then:
        !ec.message.hasError()
        result.serviceRequestList.find { it.serviceRequestId == serviceRequestId } != null
    }

    def "Technician 2 (not assigned) does not see the Service Request via find"() {
        when:
        ElectricalServiceTestSupport.login(ec, ElectricalServiceTestSupport.TECH2_USERNAME, ElectricalServiceTestSupport.TECH2_PASSWORD)
        Map result = ec.service.sync().name("ElectricalService.ElectricalServiceServices.find#ServiceRequests").parameters([:]).call()

        then:
        !ec.message.hasError()
        result.serviceRequestList.find { it.serviceRequestId == serviceRequestId } == null
    }

    def "Technician 2 (not assigned) does not see the Service Request via get"() {
        when:
        ElectricalServiceTestSupport.login(ec, ElectricalServiceTestSupport.TECH2_USERNAME, ElectricalServiceTestSupport.TECH2_PASSWORD)
        Throwable thrown = null
        Map result = null
        try {
            result = ec.service.sync().name("ElectricalService.ElectricalServiceServices.get#ServiceRequest")
                    .parameters([serviceRequestId: serviceRequestId]).call()
        } catch (Throwable t) { thrown = t }

        then:
        // filtered out by EntityFilterSet (not found), or blocked outright - either way not readable
        thrown != null || ec.message.hasError() || result?.serviceRequest == null
    }

    def "Dispatcher sees the Service Request regardless of technician assignment"() {
        when:
        ElectricalServiceTestSupport.login(ec, ElectricalServiceTestSupport.DISPATCHER_USERNAME, ElectricalServiceTestSupport.DISPATCHER_PASSWORD)
        Map result = ec.service.sync().name("ElectricalService.ElectricalServiceServices.find#ServiceRequests").parameters([:]).call()

        then:
        !ec.message.hasError()
        result.serviceRequestList.find { it.serviceRequestId == serviceRequestId } != null
    }

    def "Technician 2 cannot start Technician 1's Visit"() {
        when:
        ElectricalServiceTestSupport.login(ec, ElectricalServiceTestSupport.TECH2_USERNAME, ElectricalServiceTestSupport.TECH2_PASSWORD)
        Throwable thrown = null
        try {
            ec.service.sync().name("ElectricalService.ElectricalServiceServices.start#Visit")
                    .parameters([serviceVisitId: serviceVisitId]).call()
        } catch (Throwable t) { thrown = t }

        then:
        thrown != null || ec.message.hasError()
        findWorkEffort(ec, serviceVisitId).statusId == "EsvScheduled"
    }

    def "Technician 2 cannot complete Technician 1's Visit"() {
        when:
        ElectricalServiceTestSupport.login(ec, ElectricalServiceTestSupport.TECH2_USERNAME, ElectricalServiceTestSupport.TECH2_PASSWORD)
        Throwable thrown = null
        try {
            ec.service.sync().name("ElectricalService.ElectricalServiceServices.complete#Visit")
                    .parameters([serviceVisitId: serviceVisitId, workNotes: "should not be allowed"]).call()
        } catch (Throwable t) { thrown = t }

        then:
        thrown != null || ec.message.hasError()
        findWorkEffort(ec, serviceVisitId).statusId == "EsvScheduled"
    }

    def "Technician cannot schedule a Visit (Dispatcher-only action)"() {
        when:
        ElectricalServiceTestSupport.login(ec, ElectricalServiceTestSupport.TECH1_USERNAME, ElectricalServiceTestSupport.TECH1_PASSWORD)
        Throwable thrown = null
        try {
            ec.service.sync().name("ElectricalService.ElectricalServiceServices.schedule#Visit")
                    .parameters([serviceRequestId: serviceRequestId, scheduledDate: ec.user.nowTimestamp]).call()
        } catch (Throwable t) { thrown = t }

        then:
        thrown != null || ec.message.hasError()
    }

    def "unauthenticated caller cannot create a Service Request"() {
        when:
        ElectricalServiceTestSupport.logout(ec)
        Throwable thrown = null
        try {
            ec.service.sync().name("ElectricalService.ElectricalServiceServices.create#ServiceRequest")
                    .parameters([customerPartyId: ElectricalServiceTestSupport.CUSTOMER1_PARTY_ID, description: "no auth"]).call()
        } catch (Throwable t) { thrown = t }

        then:
        thrown != null || ec.message.hasError()
    }
}
