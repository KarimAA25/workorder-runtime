/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

/* Run with (from repo root): gradlew.bat cleanAll load -Ptypes=seed,demo
    then: gradlew.bat :runtime:component:ElectricalService:test
   Fast iterate: gradlew.bat loadSave once, then gradlew.bat reloadSave :runtime:component:ElectricalService:test

   Note: entity lookups used only to verify state (not the service calls under test) run with
   authz disabled, same convention as framework/src/test/groovy/SecurityTestSupport.groovy -
   Dispatcher/Technician are authorized for the ElectricalService screens/services/REST paths,
   not for bare direct entity access outside of those, and that is intentional (see
   data/ElectricalServiceAaaSeedData.xml).
 */
class ElectricalServiceWorkflowTests extends Specification {
    @Shared protected final static Logger logger = LoggerFactory.getLogger(ElectricalServiceWorkflowTests.class)
    @Shared ExecutionContext ec
    @Shared String serviceRequestId
    @Shared String serviceVisitId

    def setupSpec() { ec = Moqui.getExecutionContext() }
    def cleanupSpec() { ec.destroy() }

    def cleanup() {
        ec.message.clearAll()
        ElectricalServiceTestSupport.logout(ec)
    }

    static EntityValue findRequest(ExecutionContext ec, String requestId) {
        EntityValue ev = null
        ElectricalServiceTestSupport.withAuthzDisabled(ec) {
            ev = ec.entity.find("mantle.request.Request").condition("requestId", requestId).one()
        }
        return ev
    }
    static EntityValue findWorkEffort(ExecutionContext ec, String workEffortId) {
        EntityValue ev = null
        ElectricalServiceTestSupport.withAuthzDisabled(ec) {
            ev = ec.entity.find("mantle.work.effort.WorkEffort").condition("workEffortId", workEffortId).one()
        }
        return ev
    }
    static EntityValue findVisit(ExecutionContext ec, String workEffortId) {
        EntityValue ev = null
        ElectricalServiceTestSupport.withAuthzDisabled(ec) {
            ev = ec.entity.find("ElectricalService.ElectricalServiceVisit").condition("workEffortId", workEffortId).one()
        }
        return ev
    }

    def "Dispatcher creates a Service Request"() {
        when:
        ElectricalServiceTestSupport.login(ec, ElectricalServiceTestSupport.DISPATCHER_USERNAME, ElectricalServiceTestSupport.DISPATCHER_PASSWORD)
        Map result = ec.service.sync().name("ElectricalService.ElectricalServiceServices.create#ServiceRequest")
                .parameters([customerPartyId: ElectricalServiceTestSupport.CUSTOMER1_PARTY_ID,
                        description: "No power in the kitchen", priority: 3]).call()
        serviceRequestId = result?.serviceRequestId

        then:
        !ec.message.hasError()
        serviceRequestId != null
        EntityValue request = findRequest(ec, serviceRequestId)
        request.statusId == "EsrOpen"
        request.requestTypeEnumId == "RtElecService"
    }

    def "create Service Request rejects a non-Customer party"() {
        when:
        ElectricalServiceTestSupport.login(ec, ElectricalServiceTestSupport.DISPATCHER_USERNAME, ElectricalServiceTestSupport.DISPATCHER_PASSWORD)
        Map result = ec.service.sync().name("ElectricalService.ElectricalServiceServices.create#ServiceRequest")
                .parameters([customerPartyId: ElectricalServiceTestSupport.TECH1_PARTY_ID, description: "bad customer"]).call()

        then:
        ec.message.hasError()
        result?.serviceRequestId == null
    }

    def "Dispatcher assigns a Technician"() {
        when:
        ElectricalServiceTestSupport.login(ec, ElectricalServiceTestSupport.DISPATCHER_USERNAME, ElectricalServiceTestSupport.DISPATCHER_PASSWORD)
        ec.service.sync().name("ElectricalService.ElectricalServiceServices.assign#Technician")
                .parameters([serviceRequestId: serviceRequestId, technicianPartyId: ElectricalServiceTestSupport.TECH1_PARTY_ID]).call()

        then:
        !ec.message.hasError()
        findRequest(ec, serviceRequestId).statusId == "EsrAssigned"
    }

    def "assign#Technician rejects a non-Worker party"() {
        when:
        ElectricalServiceTestSupport.login(ec, ElectricalServiceTestSupport.DISPATCHER_USERNAME, ElectricalServiceTestSupport.DISPATCHER_PASSWORD)
        ec.service.sync().name("ElectricalService.ElectricalServiceServices.assign#Technician")
                .parameters([serviceRequestId: serviceRequestId, technicianPartyId: ElectricalServiceTestSupport.CUSTOMER1_PARTY_ID]).call()

        then:
        ec.message.hasError()
    }

    def "Technician cannot assign a Technician (Dispatcher-only action)"() {
        when:
        ElectricalServiceTestSupport.login(ec, ElectricalServiceTestSupport.TECH1_USERNAME, ElectricalServiceTestSupport.TECH1_PASSWORD)
        Throwable thrown = null
        try {
            ec.service.sync().name("ElectricalService.ElectricalServiceServices.assign#Technician")
                    .parameters([serviceRequestId: serviceRequestId, technicianPartyId: ElectricalServiceTestSupport.TECH1_PARTY_ID]).call()
        } catch (Throwable t) { thrown = t }

        then:
        thrown != null || ec.message.hasError()
    }

    def "Dispatcher schedules the Visit"() {
        when:
        ElectricalServiceTestSupport.login(ec, ElectricalServiceTestSupport.DISPATCHER_USERNAME, ElectricalServiceTestSupport.DISPATCHER_PASSWORD)
        Map result = ec.service.sync().name("ElectricalService.ElectricalServiceServices.schedule#Visit")
                .parameters([serviceRequestId: serviceRequestId, scheduledDate: ec.user.nowTimestamp]).call()
        serviceVisitId = result?.serviceVisitId

        then:
        !ec.message.hasError()
        serviceVisitId != null
        findRequest(ec, serviceRequestId).statusId == "EsrScheduled"
        EntityValue workEffort = findWorkEffort(ec, serviceVisitId)
        workEffort.statusId == "EsvScheduled"
        workEffort.workEffortTypeEnumId == "WetElecVisit"
    }

    def "schedule#Visit rejects a Request that is not Assigned"() {
        when:
        ElectricalServiceTestSupport.login(ec, ElectricalServiceTestSupport.DISPATCHER_USERNAME, ElectricalServiceTestSupport.DISPATCHER_PASSWORD)
        // serviceRequestId is already Scheduled at this point in the flow, so scheduling again is invalid
        Map result = ec.service.sync().name("ElectricalService.ElectricalServiceServices.schedule#Visit")
                .parameters([serviceRequestId: serviceRequestId, scheduledDate: ec.user.nowTimestamp]).call()

        then:
        ec.message.hasError()
        result?.serviceVisitId == null
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

    def "Technician 1 starts the Visit"() {
        when:
        ElectricalServiceTestSupport.login(ec, ElectricalServiceTestSupport.TECH1_USERNAME, ElectricalServiceTestSupport.TECH1_PASSWORD)
        ec.service.sync().name("ElectricalService.ElectricalServiceServices.start#Visit")
                .parameters([serviceVisitId: serviceVisitId]).call()

        then:
        !ec.message.hasError()
        EntityValue workEffort = findWorkEffort(ec, serviceVisitId)
        workEffort.statusId == "EsvInProgress"
        workEffort.actualStartDate != null
        findRequest(ec, serviceRequestId).statusId == "EsrInProgress"
    }

    def "start#Visit rejects starting an already In Progress Visit"() {
        when:
        ElectricalServiceTestSupport.login(ec, ElectricalServiceTestSupport.TECH1_USERNAME, ElectricalServiceTestSupport.TECH1_PASSWORD)
        ec.service.sync().name("ElectricalService.ElectricalServiceServices.start#Visit")
                .parameters([serviceVisitId: serviceVisitId]).call()

        then:
        ec.message.hasError()
    }

    def "Technician 1 completes the Visit with work notes"() {
        when:
        ElectricalServiceTestSupport.login(ec, ElectricalServiceTestSupport.TECH1_USERNAME, ElectricalServiceTestSupport.TECH1_PASSWORD)
        ec.service.sync().name("ElectricalService.ElectricalServiceServices.complete#Visit")
                .parameters([serviceVisitId: serviceVisitId, workNotes: "Replaced breaker, tested outlets."]).call()

        then:
        !ec.message.hasError()
        EntityValue workEffort = findWorkEffort(ec, serviceVisitId)
        workEffort.statusId == "EsvCompleted"
        workEffort.actualCompletionDate != null
        findVisit(ec, serviceVisitId).workNotes == "Replaced breaker, tested outlets."
        findRequest(ec, serviceRequestId).statusId == "EsrCompleted"
    }

    def "Technician cannot close a Service Request (Dispatcher-only action)"() {
        when:
        ElectricalServiceTestSupport.login(ec, ElectricalServiceTestSupport.TECH1_USERNAME, ElectricalServiceTestSupport.TECH1_PASSWORD)
        Throwable thrown = null
        try {
            ec.service.sync().name("ElectricalService.ElectricalServiceServices.close#ServiceRequest")
                    .parameters([serviceRequestId: serviceRequestId]).call()
        } catch (Throwable t) { thrown = t }

        then:
        thrown != null || ec.message.hasError()
        findRequest(ec, serviceRequestId).statusId == "EsrCompleted"
    }

    def "Dispatcher closes the Service Request"() {
        when:
        ElectricalServiceTestSupport.login(ec, ElectricalServiceTestSupport.DISPATCHER_USERNAME, ElectricalServiceTestSupport.DISPATCHER_PASSWORD)
        ec.service.sync().name("ElectricalService.ElectricalServiceServices.close#ServiceRequest")
                .parameters([serviceRequestId: serviceRequestId]).call()

        then:
        !ec.message.hasError()
        findRequest(ec, serviceRequestId).statusId == "EsrClosed"
    }

    def "close#ServiceRequest rejects a Request that is not Completed"() {
        when:
        ElectricalServiceTestSupport.login(ec, ElectricalServiceTestSupport.DISPATCHER_USERNAME, ElectricalServiceTestSupport.DISPATCHER_PASSWORD)
        // a fresh Request is EsrOpen; EsrOpen -> EsrClosed has no seeded StatusFlowTransition
        Map createResult = ec.service.sync().name("ElectricalService.ElectricalServiceServices.create#ServiceRequest")
                .parameters([customerPartyId: ElectricalServiceTestSupport.CUSTOMER1_PARTY_ID, description: "not yet completed"]).call()
        String freshRequestId = createResult.serviceRequestId
        ec.message.clearErrors()
        ec.service.sync().name("ElectricalService.ElectricalServiceServices.close#ServiceRequest")
                .parameters([serviceRequestId: freshRequestId]).call()

        then:
        ec.message.hasError()
        findRequest(ec, freshRequestId).statusId == "EsrOpen"
    }
}
