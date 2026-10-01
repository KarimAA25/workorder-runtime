# Billing Integration (documentation only, not implemented)

ElectricalService does not implement any accounting. This note describes how a
completed request or visit could flow into Mantle's existing order/invoice/payment
functionality, naming the real entities and services involved.

## Option A: time-based billing (recommended fit)

An `ElectricalServiceVisit` is a `mantle.work.effort.WorkEffort` (type `WetElecVisit`).
Mantle already has a "log hours against a WorkEffort, then bill those hours" path:

1. When a technician completes a visit, a `mantle.work.time.TimeEntry` would be created
   with `workEffortId` = the visit's `workEffortId`, `partyId` = the technician
   (`ElectricalServiceVisitDetail.technicianPartyId`), `clientPartyId`/billing party =
   the request's customer (`ElectricalServiceRequestDetail.customerPartyId`), and
   `fromDate`/`thruDate`/`hours` covering the visit's `actualStartDate`..
   `actualCompletionDate` (`ElectricalServiceVisitDetail.startTime`/`endTime`).
2. `mantle.account.InvoiceServices.create#InvoiceBillThroughItems`
   (`runtime/component/mantle-usl/service/mantle/account/InvoiceServices.xml`) already
   knows how to turn outstanding `TimeEntry` rows into `mantle.account.invoice.Invoice`
   / `InvoiceItem` records (`itemTypeEnumId=ItemTimeEntry`).
3. Nothing about this requires changes to `mantle.work.effort.WorkEffort` or
   `ElectricalServiceVisit` - the `TimeEntry` creation step is the only new piece, and it
   is a standard Mantle entity already designed for exactly this purpose.

## Option B: order-based billing

Alternatively, a completed `ElectricalServiceRequest` (a `mantle.request.Request`) could
generate a `mantle.order.OrderHeader` / `OrderItem` (see
`runtime/component/mantle-usl/entity/OrderEntities.xml` and the order-creation services
in `mantle-usl/service/mantle/order/`), similar to how `RequestItem` /
`RequestItemOrder` already link a Request to Order items for product-oriented requests.
This fits better if visits are priced as flat-rate service line items rather than by
logged hours.

## Why this is out of scope here

Both paths are standard, already-built Mantle mechanisms - adding either now would mean
guessing at pricing/terms policy that has not been specified. The integration point is
narrow (one `TimeEntry` create call, or one `OrderHeader`/`OrderItem` create call, at
`complete#Visit` or `close#ServiceRequest` time) and can be added later without changing
any of the entities or services in this component.
