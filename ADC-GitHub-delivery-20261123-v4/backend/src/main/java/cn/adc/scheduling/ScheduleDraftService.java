package cn.adc.scheduling;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.AuditStore;
import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.audit.UcAuditService;
import cn.adc.shared.config.ClinicDirectoryProvider;
import cn.adc.shared.config.ConfigurationUnavailable;
import cn.adc.shared.config.DentistEligibilityProvider;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.time.StrictInputPolicy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.BiFunction;

import static cn.adc.shared.audit.AuditDocuments.JSON;

/** UC21 only. Dependency sources are trusted server components, never body/header assertions. */
final class ScheduleDraftService implements ScheduleOperations {
    static final String CREATE="DRAFT_SAVE", REVISE="DRAFT_REVISE", ISSUE="MAINTENANCE_ISSUE_SAVE";
    private static final Set<String> CHECK_FIELDS=Set.of("clinicId","dentistId","basisRef","submittedStartsAt","submittedEndsAt","coordinationBasis");
    private static final Set<String> DRAFT_FIELDS=Set.of("clinicId","dentistId","startsAt","endsAt","coordinationBasis","checkReference","previousIssueId");
    private static final Set<String> REVISE_FIELDS=Set.of("clinicId","dentistId","startsAt","endsAt","coordinationBasis","basisRef","expectedStateToken","changeBasis","previousIssueId");
    @FunctionalInterface interface Coordination {
        /** Return verified evidence only when it covers the exact proposal and conflict refs. */
        Optional<String> confirm(long clinic,long dentist,ScheduleStore.Window window,String submitted,List<ScheduleStore.Ref> conflicts);
    }
    private final JdbcTransactions tx;
    private final StaffAuthorization auth;
    private final ClinicDirectoryProvider clinics;
    private final DentistEligibilityProvider dentists;
    private final Coordination coordination;
    private final ScheduleStore store;
    private final Clock clock;
    private final BiFunction<CommandRecovery.Command,CommandRecovery.Work,CommandRecovery.Receipt> commands;

    ScheduleDraftService(JdbcTransactions tx,StaffAuthorization auth,ClinicDirectoryProvider clinics,
                         DentistEligibilityProvider dentists,Coordination coordination,Clock clock,AuditStore.EventIdAllocator auditIds) {
        this(tx,auth,clinics,dentists,coordination,clock,new JdbcScheduleStore(),
                verifier->new UcAuditService(tx,auth,verifier,clock,Objects.requireNonNull(auditIds)).commands()::executeUc21);
    }

    ScheduleDraftService(JdbcTransactions tx,StaffAuthorization auth,ClinicDirectoryProvider clinics,
                         DentistEligibilityProvider dentists,Coordination coordination,Clock clock,
                         ScheduleStore store,java.util.function.Function<CommandRecovery.SuccessVerifier,
                                BiFunction<CommandRecovery.Command,CommandRecovery.Work,CommandRecovery.Receipt>> commands) {
        this.tx=Objects.requireNonNull(tx); this.auth=Objects.requireNonNull(auth);
        this.clinics=Objects.requireNonNull(clinics); this.dentists=Objects.requireNonNull(dentists);
        this.coordination=Objects.requireNonNull(coordination); this.clock=Objects.requireNonNull(clock);
        this.store=Objects.requireNonNull(store);
        this.commands=Objects.requireNonNull(commands.apply(new SuccessFacts()));
    }

    public JsonNode list(Long clinic,Long dentist,String from,String to,String cursor) {
        var scopes=clinic==null?auth.uc21Clinics():auth.require("UC21",Set.of(clinic)).clinicIds();
        var start=from==null?null:StrictInputPolicy.instant(from); var end=to==null?null:StrictInputPolicy.instant(to);
        if(start!=null&&end!=null&&!start.isBefore(end))throw new IllegalArgumentException("INVALID_WINDOW");
        var after=cursor==null?null:cursor(cursor);
        return tx.execute(c->{
            var refs=store.list(c,scopes,dentist,start,end,after);
            var out=JSON.createObjectNode(); var items=out.putArray("items");
            var cache=new HashMap<Long,List<ScheduleStateToken.Version>>();
            for(var ref:refs.stream().limit(50).toList()){
                var rows=cache.get(ref.id()); if(rows==null){rows=store.versions(c,ref.id());cache.put(ref.id(),rows);}
                var v=select(rows,ref);
                if(!scopes.contains(v.clinicId()))throw denied();
                items.add(view(v,rows));
            }
            if(refs.size()>50){var next=refs.get(49);out.put("nextCursor",next.id()+":"+next.version());}
            auth.require("UC21",scopes);
            return out;
        });
    }
    public JsonNode version(long id,int no) {
        var scopes=auth.uc21Clinics();
        return tx.execute(c->{var rows=store.versions(c,id);var v=select(rows,new ScheduleStore.Ref(id,no));
            if(!scopes.contains(v.clinicId()))throw reject("NOT_FOUND");
            auth.require("UC21",Set.of(v.clinicId()));return view(v,rows);});
    }
    public JsonNode issue(long id) {
        var scopes=auth.uc21Clinics();
        return tx.execute(c->{var i=requiredIssue(c,id);
            if(!scopes.contains(i.clinic()))throw reject("NOT_FOUND");
            auth.require("UC21",Set.of(i.clinic()));return issueView(i);});
    }

    public JsonNode check(JsonNode input) {
        fields(input,CHECK_FIELDS); long clinic=id(input,"clinicId");
        auth.require("UC21",Set.of(clinic));
        return tx.execute(c->{
            var out=JSON.createObjectNode();var checks=out.putObject("checks");var conflicts=out.putArray("conflicts");
            try {
                var basis=optionalRef(input,"basisRef");
                long basisClinic=basis==null?clinic:select(store.versions(c,basis.id()),basis).clinicId();
                var scopes=new HashSet<Long>(Set.of(clinic));scopes.add(basisClinic);
                auth.require("UC21",scopes);
                long dentist=id(input,"dentistId");
                var window=window(clinic,text(input,"submittedStartsAt"),text(input,"submittedEndsAt"));
                var evaluation=evaluate(c,clinic,dentist,window,basis,optional(input,"coordinationBasis"));
                checks.put("qualification","VERIFIED");checks.put("resource",evaluation.conflicts().isEmpty()?"CLEAR":"OVERLAP");
                checks.put("coordination",evaluation.coordinationRequired()?(evaluation.evidence().isPresent()?"VERIFIED":"REQUIRED"):"NOT_REQUIRED");
                if(!evaluation.conflicts().isEmpty())conflicts.addObject().put("code","SCHEDULE_OVERLAP")
                        .put("message","检测到时间窗口重叠，草案不构成可用排班");
                addImpact(c,basisClinic,clinic,dentist,window,basis,conflicts);
                auth.require("UC21",scopes);
                out.put("conclusion",evaluation.blocked()?"NOT_PASSED":"PASSED");
            }catch(ConfigurationUnavailable e){checks.put("authority","UNAVAILABLE");out.put("conclusion","POLICY_UNRESOLVED");}
            catch(IllegalArgumentException e){checks.put("input","INVALID_OR_INCOMPLETE");out.put("conclusion","NOT_PASSED");}
            auth.require("UC21",Set.of(clinic));return out;
        });
    }

    public CommandRecovery.Receipt create(UUID key,JsonNode input) {
        fields(input,DRAFT_FIELDS);var clinic=id(input,"clinicId");var dentist=id(input,"dentistId");
        String start=text(input,"startsAt"),end=text(input,"endsAt");var previous=optionalId(input,"previousIssueId");
        var payload=normalized(input,"clinicId","dentistId","previousIssueId");
        var cmd=command(CREATE,key,payload,Set.of(clinic));
        return saveCommand(cmd,(c,locks)->{
            locks.uc21NewDraft(dentist);
            auth.require("UC21",Set.of(clinic));
            var prior=previous==null?null:issueChain(c,previous,clinic);
            if(prior!=null&&prior.basis()!=null)throw reject("EXISTING_BASIS_REQUIRES_REVISION");
            if(prior!=null&&prior.result()!=null)throw reject("ISSUE_ALREADY_LINKED");
            var w=window(clinic,start,end);
            var facts=evaluate(c,clinic,dentist,w,null,optional(input,"coordinationBasis"));
            requireSavable(facts);
            long id=store.nextId(c,false);
            return saveDraft(c,id,1,clinic,dentist,w,facts,prior,null,clinic,null);
        });
    }

    public CommandRecovery.Receipt revise(UUID key,long id,JsonNode input) {
        fields(input,REVISE_FIELDS);var basis=ref(input.path("basisRef"));
        if(basis.id()!=id)throw reject("BASIS_MISMATCH");
        var clinic=id(input,"clinicId");var dentist=id(input,"dentistId");
        var token=text(input,"expectedStateToken");var changeBasis=text(input,"changeBasis");
        var previous=optionalId(input,"previousIssueId");
        var start=text(input,"startsAt");var end=text(input,"endsAt");
        var payload=normalized(input,"clinicId","dentistId","previousIssueId");
        payload.set("basisRef",refNode(basis));payload.put("pathScheduleId",Long.toString(id));
        // A pre-read discovers the authorized basis clinic before START; it does not decide the write.
        auth.uc21Clinics();
        var before=tx.execute(c->{var v=select(store.versions(c,id),basis);auth.require("UC21",Set.of(v.clinicId()));return v;});
        var scopes=new HashSet<Long>(Set.of(clinic));scopes.add(before.clinicId());
        var cmd=command(REVISE,key,payload,scopes);
        return saveCommand(cmd,(c,locks)->{
            locks.uc21ExistingDraft(List.of(before.dentistId(),dentist),new ResourceLockCoordinator.Version(id,basis.version()));
            auth.require("UC21",scopes);
            var rows=store.versions(c,id);var current=select(rows,basis);
            if(current.dentistId()!=before.dentistId()||current.clinicId()!=before.clinicId()||
                    !token.equals(ScheduleStateToken.of(current,rows)))throw reject("STALE_VERSION");
            if(!"EFFECTIVE".equals(current.status())&&!"DRAFT".equals(current.status()))
                throw reject("HISTORICAL_BASIS_NOT_ELIGIBLE");
            // Historic draft reuse policy is still unapproved; do not allow an ambiguous old draft branch.
            if(current.status().equals("DRAFT")&&rows.stream().anyMatch(v->v.versionNo()>current.versionNo()))
                throw reject("HISTORICAL_BASIS_NOT_ELIGIBLE");
            var prior=previous==null?null:issueChain(c,previous,clinic);
            if(prior!=null&&!basis.equals(prior.basis()))throw reject("ISSUE_BASIS_MISMATCH");
            if(prior!=null&&prior.result()!=null)throw reject("ISSUE_ALREADY_LINKED");
            var w=window(clinic,start,end);var facts=evaluate(c,clinic,dentist,w,basis,optional(input,"coordinationBasis"));
            requireSavable(facts);
            int next=Math.addExact(rows.stream().mapToInt(ScheduleStateToken.Version::versionNo).max().orElseThrow(),1);
            return saveDraft(c,id,next,clinic,dentist,w,facts,prior,basis,current.clinicId(),changeBasis);
        });
    }

    private CommandRecovery.Receipt saveCommand(CommandRecovery.Command command,CommandRecovery.Work work){
        var receipt=commands.apply(command,work);
        // The audit is already durable. A changed grant only suppresses disclosure.
        if("SUCCEEDED".equals(receipt.businessOutcome()))auth.require("UC21",command.clinics());
        return receipt;
    }

    public CommandRecovery.Receipt recordIssue(UUID key,JsonNode input) {
        fields(input,Set.of("checkReference","submittedContext","failureReason","previousIssueId"));
        var submitted=input.path("submittedContext");fields(submitted,CHECK_FIELDS);
        var clinic=id(submitted,"clinicId");String reason=text(input,"failureReason");var previous=optionalId(input,"previousIssueId");
        var supplied=optionalRef(submitted,"basisRef");var payload=normalized(input,"previousIssueId");
        var context=normalized(submitted,"clinicId","dentistId");
        if(supplied!=null)context.set("basisRef",refNode(supplied));
        payload.set("submittedContext",context);
        var scope=new HashSet<Long>(Set.of(clinic));
        ScheduleStore.Ref expectedBasis=supplied;
        if(previous!=null||supplied!=null){
            auth.require("UC21",Set.of(clinic));
            try{
                expectedBasis=tx.execute(c->{
                    var existing=previous==null?supplied:issueChain(c,previous,clinic).basis();
                    if(existing!=null)scope.add(select(store.versions(c,existing.id()),existing).clinicId());
                    auth.require("UC21",scope);
                    return existing;
                });
            }catch(CommandRecovery.Rejection e){
                throw reject("ISSUE_BASIS_UNVERIFIED");
            }
        }
        var verifiedBasis=expectedBasis;
        var cmd=command(ISSUE,key,payload,scope);
        // The reference is stable before allocating any issue ID.
        String actor=auth.evaluate("UC21",scope).actor();
        String request=AuditDocuments.requestReference(actor,"UC",ISSUE,key).replace("/v1/","/v1/UC21/");
        return commands.apply(cmd,(c,locks)->{
            var authorized=auth.require("UC21",scope);
            if(!actor.equals(authorized.actor()))throw denied();
            var prior=previous==null?null:issueChain(c,previous,clinic);
            var basis=prior==null?supplied:prior.basis();
            if(prior!=null&&supplied!=null&&!supplied.equals(basis))throw reject("ISSUE_BASIS_MISMATCH");
            if(!Objects.equals(basis,verifiedBasis))throw reject("ISSUE_BASIS_MISMATCH");
            if(basis!=null&&!scope.contains(select(store.versions(c,basis.id()),basis).clinicId()))throw denied();
            Long dentist=optionalId(context,"dentistId");
            Long verifiedDentist=null;
            if(dentist!=null)try{verifiedDentist=eligibility(clinic,dentist).dentistId();}
            catch(ConfigurationUnavailable|IllegalArgumentException ignored){/* Preserve the original input without a false FK. */}
            var issue=new ScheduleStore.Issue(store.nextId(c,true),request,clinic,verifiedDentist,basis,null,previous,reason,
                    context.toString(),actor,StrictInputPolicy.eventTime(clock),possibleTime(context,"submittedStartsAt"),possibleTime(context,"submittedEndsAt"));
            store.insertIssue(c,issue);
            var evidence=issueFact(issue);
            return new AuditDocuments.Change(JSON.createObjectNode().put("issueId",Long.toString(issue.id())),JSON.nullNode(),
                    evidence,JSON.createObjectNode().put("fact","UC21_MAINTENANCE_ISSUE"));
        });
    }

    private record Evaluation(List<ScheduleStateToken.Version> conflicts, boolean localEffectiveConflict,
                              boolean coordinationRequired,Optional<String> evidence,
                              String qualification) {
        boolean blocked(){return localEffectiveConflict||(coordinationRequired&&evidence.isEmpty());}
    }
    private static void requireSavable(Evaluation facts){
        if(facts.localEffectiveConflict())throw reject("EFFECTIVE_SCHEDULE_CONFLICT");
        if(facts.blocked())throw reject("COORDINATION_REQUIRED");
    }
    private Evaluation evaluate(Connection c,long clinic,long dentist,ScheduleStore.Window window,ScheduleStore.Ref basis,String submitted)throws SQLException {
        var qualification=eligibility(clinic,dentist);
        var found=store.overlaps(c,dentist,window).stream().filter(v->basis==null
                ||(!key(v).equals(basis)&&!(v.scheduleId()==basis.id()&&v.status().equals("EFFECTIVE")))).toList();
        boolean local=found.stream().anyMatch(v->v.clinicId()==clinic&&v.status().equals("EFFECTIVE"));
        var needs=found.stream().filter(v->v.clinicId()!=clinic&&v.status().equals("EFFECTIVE")).map(ScheduleDraftService::key).toList();
        var evidence=needs.isEmpty()||submitted==null?Optional.<String>empty():coordination.confirm(clinic,dentist,window,submitted,needs)
                .filter(s->!s.isBlank());
        return new Evaluation(found,local,!needs.isEmpty(),evidence,qualification.evidenceReference());
    }
    private DentistEligibilityProvider.Eligibility eligibility(long clinic,long dentist) {
        var v=dentists.require(dentist,clinic);
        if(v==null||v.provenance()==null)throw new ConfigurationUnavailable();
        v.provenance().requireUsable();
        if(v.clinicId()!=clinic||v.dentistId()!=dentist||v.evidenceReference()==null||v.evidenceReference().isBlank())
            throw new ConfigurationUnavailable();
        return v;
    }
    private ScheduleStore.Window window(long clinic,String start,String end) {
        var v=clinics.find(clinic);
        if(v==null||v.id()!=clinic||v.zone()==null||v.provenance()==null)throw new ConfigurationUnavailable();
        v.provenance().requireUsable();
        var a=instantInZone(start,v.zone());var b=instantInZone(end,v.zone());
        if(!a.isBefore(b))throw reject("INVALID_WINDOW");
        return new ScheduleStore.Window(a,b);
    }
    private static Instant instantInZone(String value,ZoneId zone) {
        var instant=StrictInputPolicy.instant(value);var offset=OffsetDateTime.parse(StrictInputPolicy.microsecondText(value));
        if(!zone.getRules().getValidOffsets(offset.toLocalDateTime()).contains(offset.getOffset()))
            throw reject("OFFSET_NOT_VALID_FOR_CLINIC");
        return instant;
    }
    private AuditDocuments.Change saveDraft(Connection c,long id,int version,long clinic,long dentist,ScheduleStore.Window w,
                                             Evaluation facts,ScheduleStore.Issue prior,ScheduleStore.Ref basis,long basisClinic,
                                             String changeBasis)throws SQLException {
        var draft=new ScheduleStateToken.Version(id,version,clinic,dentist,w.start(),w.end(),"DRAFT",
                facts.evidence().orElse(null),facts.qualification(),facts.conflicts().isEmpty()?"CLEAR":"OVERLAP",null,null);
        store.insertDraft(c,draft);
        var result=key(draft);
        if(prior!=null&&!store.linkIssue(c,prior,result))throw reject("ISSUE_ALREADY_LINKED");
        var after=draftFact(draft);if(prior!=null)after.put("linkedIssueId",Long.toString(prior.id()));
        var proof=JSON.createObjectNode().put("fact","UC21_DRAFT").put("originalStatus","DRAFT")
                .put("impactClinicId",Long.toString(basisClinic));
        if(changeBasis!=null)proof.put("changeBasis",changeBasis);
        var ids=after.putObject("appointmentImpact").putArray("appointmentIds");
        if(basis!=null)store.affectedAppointments(c,basisClinic,clinic,id,dentist,w,false).forEach(ids::add);
        return new AuditDocuments.Change(refNode(result),basis==null?JSON.nullNode():refNode(basis),after,proof);
    }
    private void addImpact(Connection c,long basisClinic,long clinic,long dentist,ScheduleStore.Window window,ScheduleStore.Ref basis,
                           tools.jackson.databind.node.ArrayNode out)throws SQLException {
        if(basis==null)return;
        var affected=store.affectedAppointments(c,basisClinic,clinic,basis.id(),dentist,window,true);
        affected.stream().limit(100).forEach(id->out.addObject().put("code","POTENTIAL_APPOINTMENT_IMPACT")
                .put("message","本诊所预约 "+id+" 需在生效前复核"));
        if(affected.size()>100)out.addObject().put("code","IMPACT_LIST_LIMIT").put("message","影响清单未全部展示，生效前须完整复核");
    }
    private ScheduleStore.Issue issueChain(Connection c,long id,long clinic)throws SQLException {
        var first=requiredIssue(c,id);var seen=new HashSet<Long>();var next=first;
        while(next!=null){
            if(next.clinic()!=clinic)throw denied();
            if(!seen.add(next.id())||seen.size()>128||!Objects.equals(first.basis(),next.basis()))
                throw reject("ISSUE_CHAIN_UNVERIFIED");
            next=next.previous()==null?null:requiredIssue(c,next.previous());
        }
        return first;
    }
    private ScheduleStore.Issue requiredIssue(Connection c,long id)throws SQLException {
        var i=store.issue(c,id);if(i==null)throw reject("NOT_FOUND");return i;
    }
    private static ScheduleStateToken.Version select(List<ScheduleStateToken.Version> rows,ScheduleStore.Ref ref){
        return rows.stream().filter(v->key(v).equals(ref)).findFirst().orElseThrow(()->reject("NOT_FOUND"));
    }
    private static ScheduleStore.Ref key(ScheduleStateToken.Version v){return new ScheduleStore.Ref(v.scheduleId(),v.versionNo());}
    private static ScheduleStore.Ref cursor(String cursor){
        var p=cursor.split(":",-1);if(p.length!=2)throw new IllegalArgumentException("INVALID_CURSOR");
        return new ScheduleStore.Ref(decimal(p[0]),Integer.parseInt(p[1]));
    }
    private static ObjectNode view(ScheduleStateToken.Version v,List<ScheduleStateToken.Version> rows) {
        var out=JSON.createObjectNode();out.set("ref",refNode(key(v)));
        out.put("clinicId",Long.toString(v.clinicId())).put("dentistId",Long.toString(v.dentistId()))
                .put("startsAt",v.startsAt().toString()).put("endsAt",v.endsAt().toString())
                .put("statusLabel",switch(v.status()){
                    case "DRAFT"->"待生效草案";case "EFFECTIVE"->"已生效";case "SUPERSEDED"->"历史版本";default->"待核对";
                }).put("stateToken",ScheduleStateToken.of(v,rows));
        if(Set.of("DRAFT","EFFECTIVE","SUPERSEDED").contains(v.status()))out.put("activationStatus",v.status());
        if(v.activatedAt()!=null)out.put("activatedAt",v.activatedAt().toString());
        return out;
    }
    private static ObjectNode issueView(ScheduleStore.Issue i) {
        var n=JSON.createObjectNode().put("issueId",Long.toString(i.id())).put("clinicId",Long.toString(i.clinic()))
                .put("failureReason",i.reason()).put("recordedAt",i.at().toString());
        if(i.basis()!=null)n.set("relatedScheduleRef",refNode(i.basis()));
        if(i.result()!=null)n.set("resultingScheduleRef",refNode(i.result()));
        return n;
    }
    private static ObjectNode draftFact(ScheduleStateToken.Version v){
        var n=JSON.createObjectNode();n.set("scheduleRef",refNode(key(v)));
        return n.put("clinicId",Long.toString(v.clinicId())).put("dentistId",Long.toString(v.dentistId()))
                .put("startsAt",v.startsAt().toString()).put("endsAt",v.endsAt().toString());
    }
    private static ObjectNode issueFact(ScheduleStore.Issue i){
        var n=issueView(i);n.remove("resultingScheduleRef");
        n.put("originalRequestReference",i.reference()).put("recordedBy",i.actor()).put("context",i.context());
        if(i.dentist()!=null)n.put("verifiedDentistId",Long.toString(i.dentist()));
        if(i.requestedStart()!=null)n.put("requestedStartsAt",i.requestedStart().toString());
        if(i.requestedEnd()!=null)n.put("requestedEndsAt",i.requestedEnd().toString());
        if(i.previous()!=null)n.put("previousIssueId",Long.toString(i.previous()));
        return n;
    }
    private final class SuccessFacts implements CommandRecovery.SuccessVerifier {
        public boolean matchesCommitted(Connection c,CommandRecovery.Command command,JsonNode change)throws SQLException{
            var saved=change.path("after");
            if(!command.operation().equals(ISSUE)&&!command.operation().equals(CREATE)&&!command.operation().equals(REVISE))return false;
            try{
                long clinic=id(saved,"clinicId");
                if(!command.clinics().contains(clinic))return false;
                var proof=change.path("verification");
                if(command.operation().equals(ISSUE)){
                    var actual=store.issue(c,id(saved,"issueId"));
                    var submitted=command.payload().path("submittedContext");
                    return proof.path("fact").asText().equals("UC21_MAINTENANCE_ISSUE")
                            &&change.path("businessReference").path("issueId").equals(saved.path("issueId"))
                            &&actual!=null&&actual.clinic()==clinic&&issueFact(actual).equals(saved)
                            &&actual.actor().equals(auth.require("UC21",command.clinics()).actor())
                            &&submitted.equals(AuditDocuments.parse(actual.context()))
                            &&text(command.payload(),"failureReason").equals(actual.reason())
                            &&Objects.equals(optionalId(command.payload(),"previousIssueId"),actual.previous())
                            &&actual.reference().equals(AuditDocuments.requestReference(actual.actor(),"UC",ISSUE,command.clientKey()).replace("/v1/","/v1/UC21/"));
                }
                if(!proof.path("fact").asText().equals("UC21_DRAFT")||!proof.path("originalStatus").asText().equals("DRAFT")
                        ||!change.path("businessReference").equals(saved.path("scheduleRef"))
                        ||!command.payload().path("clinicId").equals(saved.path("clinicId")))return false;
                var k=ref(saved.path("scheduleRef"));var rows=store.versions(c,k.id());var actual=select(rows,k);
                var payload=command.payload();
                if(id(payload,"dentistId")!=actual.dentistId()
                        ||!StrictInputPolicy.instant(text(payload,"startsAt")).equals(actual.startsAt())
                        ||!StrictInputPolicy.instant(text(payload,"endsAt")).equals(actual.endsAt()))return false;
                ScheduleStore.Ref basis=null;
                long impactClinic=clinic;
                if(command.operation().equals(REVISE)){
                    basis=ref(command.payload().path("basisRef"));
                    if(k.id()!=basis.id()||k.version()<=basis.version()||!change.path("before").equals(refNode(basis)))return false;
                    impactClinic=select(rows,basis).clinicId();
                }else if(k.version()!=1||!change.path("before").isNull())return false;
                var impact=saved.path("appointmentImpact");
                if(!validAppointmentImpact(impact)||id(proof,"impactClinicId")!=impactClinic
                        ||!command.clinics().contains(impactClinic)
                        ||(basis==null&&!impact.path("appointmentIds").isEmpty()))return false;
                var fact=draftFact(actual);
                fact.set("appointmentImpact",impact);
                var linked=optionalId(saved,"linkedIssueId");
                if(!Objects.equals(linked,optionalId(payload,"previousIssueId")))return false;
                if(linked!=null){
                    var issue=store.issue(c,linked);
                    if(issue==null||issue.clinic()!=clinic||!Objects.equals(basis,issue.basis())||!k.equals(issue.result()))return false;
                    fact.put("linkedIssueId",Long.toString(issue.id()));
                }
                return fact.equals(saved);
            }catch(IllegalArgumentException e){return false;}
        }
        public boolean hasContradictorySuccess(Connection c,CommandRecovery.Command command,String request){return false;}
    }
    static boolean validAppointmentImpact(JsonNode impact){
        if(!impact.isObject()||!impact.propertyNames().equals(Set.of("appointmentIds"))
                ||!impact.path("appointmentIds").isArray())return false;
        var unique=new HashSet<Long>();
        try{
            for(var item:impact.path("appointmentIds")){
                if(!item.isString())return false;
                long id=decimal(item.asText());
                if(!Long.toString(id).equals(item.asText())||!unique.add(id))return false;
            }
            return true;
        }catch(IllegalArgumentException e){return false;}
    }
    private static CommandRecovery.Command command(String kind,UUID key,JsonNode n,Set<Long> clinics){
        return new CommandRecovery.Command(kind,"UC21",key,n,clinics);
    }
    private static ObjectNode normalized(JsonNode n,String... fields) {
        var out=(ObjectNode)n.deepCopy();for(String f:fields)if(n.has(f))out.put(f,Long.toString(id(n,f)));return out;
    }
    private static void fields(JsonNode n,Set<String> allowed){
        if(!n.isObject()||!allowed.containsAll(n.propertyNames()))throw new IllegalArgumentException("INVALID_INPUT_FIELDS");
    }
    private static ScheduleStore.Ref optionalRef(JsonNode n,String f){return n.has(f)?ref(n.path(f)):null;}
    private static ScheduleStore.Ref ref(JsonNode n){
        fields(n,Set.of("scheduleId","versionNo"));var v=n.path("versionNo");
        if(!v.isIntegralNumber()||!v.canConvertToInt())throw new IllegalArgumentException("INVALID_VERSION");
        return new ScheduleStore.Ref(id(n,"scheduleId"),v.intValue());
    }
    private static ObjectNode refNode(ScheduleStore.Ref r){
        return JSON.createObjectNode().put("scheduleId",Long.toString(r.id())).put("versionNo",r.version());
    }
    private static String text(JsonNode n,String f){
        var v=n.path(f);if(!v.isString()||v.asText().isBlank())throw new IllegalArgumentException("INPUT_REQUIRED");
        return v.asText();
    }
    private static String optional(JsonNode n,String f){return n.has(f)?text(n,f):null;}
    private static Long optionalId(JsonNode n,String f){return n.has(f)?id(n,f):null;}
    private static long id(JsonNode n,String f){return decimal(text(n,f));}
    static long decimal(String s){if(!s.matches("-?[0-9]+"))throw new IllegalArgumentException("INVALID_ID");return Long.parseLong(s);}
    private static Instant possibleTime(JsonNode n,String f){
        try{return n.has(f)?StrictInputPolicy.instant(text(n,f)):null;}catch(IllegalArgumentException e){return null;}
    }
    private static CommandRecovery.Rejection reject(String code){return new CommandRecovery.Rejection(code);}
    private static SecurityException denied(){return new SecurityException("ACTION_OR_SCOPE_DENIED");}
}
