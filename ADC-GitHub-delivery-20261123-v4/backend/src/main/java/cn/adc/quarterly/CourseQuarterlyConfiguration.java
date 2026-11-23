package cn.adc.quarterly;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffBindingAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
@org.springframework.context.annotation.Profile("course-demo")
@ConditionalOnProperty(name="adc.course.quarterly-usability-enabled",havingValue="true")
class CourseQuarterlyConfiguration {
    private final Clock clock=Clock.systemUTC();
    private final JdbcTransactions tx;
    private final java.nio.file.Path expensesPath;
    private static final String VERSION="SYNTHETIC-course-quarterly-v2";
    private static final String LEGACY="SYNTHETIC-course-quarterly-v1";
    private static final String EXPENSE_ISSUE="SYNTHETIC expense-1 review required v1";
    CourseQuarterlyConfiguration(org.springframework.core.env.Environment env)throws java.io.IOException{
        if(!"adc_course_validation".equals(env.getRequiredProperty("adc.course.database"))||env.getRequiredProperty("adc.course.database-port",Integer.class)!=55432||env.getRequiredProperty("adc.course.clinic-id",Long.class)!=81001)throw new IllegalArgumentException("QUARTER_COURSE_SCOPE_REQUIRED");
        expensesPath=java.nio.file.Path.of(env.getRequiredProperty("adc.course.quarterly-expenses"));
        if(!expensesPath.isAbsolute()||!java.nio.file.Files.isRegularFile(expensesPath)||java.nio.file.Files.size(expensesPath)>1048576)throw new IllegalArgumentException("EXPLICIT_EXPENSE_SOURCE_REQUIRED");
        var ds=new org.postgresql.ds.PGSimpleDataSource();ds.setServerNames(new String[]{"127.0.0.1"});ds.setPortNumbers(new int[]{55432});ds.setDatabaseName("adc_course_validation");ds.setUser("adc_course_quarterly");ds.setPassword(env.getRequiredProperty("ADC_COURSE_DB_QUARTERLY_PASSWORD"));ds.setOptions("-c timezone=UTC");ds.setConnectTimeout(3);ds.setSocketTimeout(10);tx=new JdbcTransactions(ds);
        QuarterExpenseCatalog.read(expensesPath);
    }
    static StaffBindingAdapter bindings(){
        var stamp=new cn.adc.shared.config.SourceStamp("SYNTHETIC course quarterly roles",VERSION,"approved course configuration",Instant.now(),true);
        return new StaffBindingAdapter(()->List.of(
          new StaffBindingAdapter.Entry("SYNTHETIC operations",new cn.adc.shared.security.StaffAuthorization.Grant(VERSION,Set.of("HQ_OPERATIONS"),Set.of("UC17","UC18","UC19"),Set.of(81001L,81002L),false),stamp),
          new StaffBindingAdapter.Entry("SYNTHETIC manager",new cn.adc.shared.security.StaffAuthorization.Grant(VERSION,Set.of("CLINIC_MANAGER"),Set.of("UC18_CORRECTION"),Set.of(81001L),false),stamp),
          new StaffBindingAdapter.Entry("SYNTHETIC finance",new cn.adc.shared.security.StaffAuthorization.Grant(VERSION,Set.of("HQ_FINANCE"),Set.of("UC20"),Set.of(81001L,81002L),false),stamp)));
    }
    static long next(Connection c,String name)throws SQLException{
        if(c.getAutoCommit())throw new SQLException("QUARTER_ID_REQUIRES_TRANSACTION","25000");
        if(!Set.of("course_quarterly_statistic_id_seq","course_quarterly_issue_id_seq","course_quarterly_correction_id_seq","course_quarterly_calculation_id_seq","course_financial_review_id_seq","course_uc_audit_id_seq").contains(name))throw new IllegalArgumentException("INVALID_QUARTER_SEQUENCE");
        try(var p=c.prepareStatement("SELECT nextval('adc."+name+"'::regclass)");var r=p.executeQuery()){if(r.next()){long id=r.getLong(1);if(id>0&&!r.next())return id;}}
        throw new SQLException("QUARTER_ID_UNAVAILABLE");
    }
    static long audit(Connection c)throws SQLException{return next(c,"course_uc_audit_id_seq");}
    @Bean QuarterlyRecordsController.Access quarterlyRecords(){
        return (req,csrf)->{
            var identity=new cn.adc.shared.security.StaffSessionIdentityAdapter(()->{
                var session=req.getSession(false);var value=session==null?null:session.getAttribute(cn.adc.shared.security.StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);
                return value instanceof cn.adc.shared.security.StaffSessionIdentityAdapter.VerifiedSession verified?verified:null;
            });
            identity.requireCsrf(csrf);
            return new QuarterlyRecordsService(tx,new cn.adc.shared.security.StaffAuthorization(identity,bindings()::bindings));
        };
    }
    @Bean QuarterlyPreviewController.Access quarterlyPreview(){
        return (req,csrf)->{
            String option=req.getParameter("expenseReview");
            if(option!=null&&!Set.of("true","false").contains(option))throw new IllegalArgumentException("INVALID_EXPENSE_REVIEW_MODE");
            return new QuarterlyPreviewController.StaffAccess(tx,bindings(),source("true".equals(option)),c->next(c,"course_quarterly_statistic_id_seq"),c->next(c,"course_quarterly_issue_id_seq"),CourseQuarterlyConfiguration::audit,clock).open(req,csrf);
        };
    }
    QuarterlyPreviewService.Sources source(boolean review){return (c,scope)->{
        if(review!=scope.expenseReview())throw new IllegalArgumentException("EXPENSE_SOURCE_MODE_MISMATCH");
        if(scope.clinics().isEmpty()||!Set.of(81001L,81002L).containsAll(scope.clinics()))throw new IllegalArgumentException("CLASSROOM_CLINIC_SCOPE");
        var window=QuarterlyWindow.of(scope.quarter());
        var facts=new java.util.ArrayList<QuarterlyPreviewService.Entry>();var corrections=new java.util.ArrayList<QuarterlyPreviewService.AppliedCorrection>();var issues=new java.util.ArrayList<QuarterlyPreviewService.Issue>();var complete=EnumSet.allOf(QuarterlyPreviewService.Metric.class);
        QuarterExpenseCatalog catalog=null;try{catalog=QuarterExpenseCatalog.read(expensesPath);}catch(IllegalArgumentException|java.io.IOException unavailable){complete.remove(QuarterlyPreviewService.Metric.EXPENSE);}
        for(long clinic:scope.clinics().stream().sorted().toList()){
            try{
                if(catalog==null)throw new IllegalArgumentException("EXPENSE_SOURCE_UNAVAILABLE");
                for(var expense:catalog.group(scope.quarter(),clinic).expenses()){
                    var applied=review&&expense.correction()!=null?applyExpenseCorrection(c,scope,expense,corrections):expenseEntry(expense);
                    facts.add(applied);
                    if(review&&expense.correction()!=null&&applied.version().equals(expense.version())){complete.remove(QuarterlyPreviewService.Metric.EXPENSE);issues.add(new QuarterlyPreviewService.Issue(clinic,expense.marker()));}
                }
            }catch(IllegalArgumentException unavailable){complete.remove(QuarterlyPreviewService.Metric.EXPENSE);issues.add(new QuarterlyPreviewService.Issue(clinic,"运营费用清单缺失或无法核实："+scope.quarter()));}
            facts.addAll(consumptionFacts(c,clinic,window));facts.addAll(billingFacts(c,clinic,window));
            int before=issues.size();facts.addAll(appointmentFacts(c,clinic,issues,window));if(issues.size()!=before)complete.remove(QuarterlyPreviewService.Metric.APPOINTMENT);
            before=issues.size();facts.addAll(performanceFacts(c,clinic,issues,window));if(issues.size()!=before)complete.remove(QuarterlyPreviewService.Metric.PERFORMANCE);
        }
        String sourceVersion=VERSION+"/"+(catalog==null?"unavailable":catalog.version())+(review?"/review":"/normal")+"/applied-"+AuditDocuments.hash(AuditDocuments.JSON.valueToTree(facts.stream().filter(f->f.version().startsWith("reviewed/")).map(f->f.clinic()+"/"+f.id()+"/"+f.version()).sorted().toList()));
        return new QuarterlyPreviewService.Manifest(scope,sourceVersion,window.start(),window.end(),
            "SYNTHETIC accrual basis; confirmed current invoice by confirmation time; verified demo receipt by receipt time; appointments by slot start; expenses exclude consumption; each invoice line is attributed once to its actual treating dentist and clinic; Asia/Shanghai natural quarter",
            complete,facts,issues,corrections);
    };}
    private static QuarterlyPreviewService.Entry expenseEntry(QuarterExpenseCatalog.Expense e){return new QuarterlyPreviewService.Entry(e.factId(),e.version(),e.clinic(),e.at(),QuarterlyPreviewService.Metric.EXPENSE,e.amount(),null,null,e.proof());}

    private static List<QuarterlyPreviewService.Entry> performanceFacts(
            Connection c, long clinic, List<QuarterlyPreviewService.Issue> issues, QuarterlyWindow window) throws SQLException {
        var facts = new java.util.ArrayList<QuarterlyPreviewService.Entry>();
        try (var sql = c.prepareStatement("""
                SELECT item.invoice_item_id, b.invoice_version_no, b.confirmed_at, item.line_amount,
                       v.actual_dentist_id,
                       COALESCE(r.visit_id=v.visit_id AND rv.responsible_dentist_id=v.actual_dentist_id
                         AND rv.implementation_result='已实施' AND btrim(v.arrival_verification)<>'',false)
                FROM adc.treatment_invoice i
                JOIN adc.visit v ON v.visit_id=i.visit_id
                JOIN adc.treatment_invoice_version b ON b.invoice_id=i.invoice_id AND b.is_current
                LEFT JOIN adc.treatment_invoice_item item ON item.invoice_id=b.invoice_id
                    AND item.invoice_version_no=b.invoice_version_no
                LEFT JOIN adc.clinical_record r ON r.clinical_record_id=item.clinical_record_id
                LEFT JOIN adc.clinical_record_version rv ON rv.clinical_record_id=item.clinical_record_id
                    AND rv.record_version_no=item.record_version_no
                WHERE v.clinic_id=? AND b.confirmation_information IS NOT NULL
                  AND b.confirmed_at>=? AND b.confirmed_at<?
                ORDER BY item.invoice_item_id
                """)) {
            sql.setLong(1, clinic);
            sql.setObject(2, window.start().atOffset(ZoneOffset.UTC));
            sql.setObject(3, window.end().atOffset(ZoneOffset.UTC));
            try (var rows = sql.executeQuery()) {
                while (rows.next()) {
                    if (!rows.getBoolean(6)) {
                        issues.add(new QuarterlyPreviewService.Issue(clinic, "已确认账单缺少可核实的诊疗业绩归属"));
                        continue;
                    }
                    facts.add(new QuarterlyPreviewService.Entry("treatment_invoice_item/" + rows.getLong(1),
                            rows.getString(2), clinic, rows.getObject(3, OffsetDateTime.class).toInstant(),
                            QuarterlyPreviewService.Metric.PERFORMANCE, rows.getBigDecimal(4), rows.getLong(5), null));
                }
            }
        }
        return facts;
    }

    private static List<QuarterlyPreviewService.Entry> appointmentFacts(
            Connection c, long clinic, List<QuarterlyPreviewService.Issue> issues, QuarterlyWindow window) throws SQLException {
        var facts = new java.util.ArrayList<QuarterlyPreviewService.Entry>();
        try (var sql = c.prepareStatement("""
                SELECT a.appointment_id, a.operated_at, a.starts_at, a.appointment_status,
                       EXISTS (SELECT 1 FROM adc.visit v WHERE v.appointment_id=a.appointment_id
                           AND v.clinic_id=s.clinic_id AND btrim(v.arrival_verification)<>'') AS attended,
                       COALESCE(btrim(a.no_show_verification)<>'',false) AS no_show,
                       COALESCE(btrim(a.cancellation_verification)<>'',false) AS cancelled
                FROM adc.appointment a
                JOIN adc.reception_schedule_version s USING (schedule_id, schedule_version_no)
                WHERE s.clinic_id=? AND a.starts_at>=? AND a.starts_at<?
                ORDER BY a.appointment_id
                """)) {
            sql.setLong(1, clinic);
            sql.setObject(2, window.start().atOffset(ZoneOffset.UTC));
            sql.setObject(3, window.end().atOffset(ZoneOffset.UTC));
            try (var rows = sql.executeQuery()) {
                while (rows.next()) {
                    long id = rows.getLong(1);
                    String state = rows.getString(4);
                    boolean attended = rows.getBoolean(5);
                    boolean noShow = rows.getBoolean(6);
                    boolean cancelled = rows.getBoolean(7);
                    var result = switch (state) {
                        case "已到诊" -> attended && !noShow && !cancelled
                                ? QuarterlyPreviewService.AppointmentResult.ATTENDED : null;
                        case "已确认未到诊" -> noShow && !attended && !cancelled
                                ? QuarterlyPreviewService.AppointmentResult.CONFIRMED_NO_SHOW : null;
                        case "已取消" -> cancelled && !attended && !noShow
                                ? QuarterlyPreviewService.AppointmentResult.CANCELLED : null;
                        default -> QuarterlyPreviewService.AppointmentResult.UNCONFIRMED;
                    };
                    if (result == null) {
                        issues.add(new QuarterlyPreviewService.Issue(clinic, "预约 " + id + " 状态与核实依据不一致"));
                        continue;
                    }
                    facts.add(new QuarterlyPreviewService.Entry("appointment/" + id,
                            rows.getObject(2, OffsetDateTime.class).toInstant().toString(), clinic,
                            rows.getObject(3, OffsetDateTime.class).toInstant(),
                            QuarterlyPreviewService.Metric.APPOINTMENT, null, null, result));
                }
            }
        }
        return facts;
    }

    private static List<QuarterlyPreviewService.Entry> billingFacts(Connection c, long clinic,QuarterlyWindow window) throws SQLException {
        var facts = new java.util.ArrayList<QuarterlyPreviewService.Entry>();
        try (var sql = c.prepareStatement("""
                SELECT 'RECEIVABLE' AS metric, 'treatment_invoice/' || i.invoice_id AS fact_id,
                       b.invoice_version_no::text AS version, b.confirmed_at AS event_time, b.total_amount AS amount
                FROM adc.treatment_invoice i
                JOIN adc.visit v ON v.visit_id=i.visit_id
                JOIN adc.treatment_invoice_version b ON b.invoice_id=i.invoice_id AND b.is_current
                WHERE v.clinic_id=? AND b.confirmation_information IS NOT NULL
                  AND b.confirmed_at>=? AND b.confirmed_at<?
                UNION ALL
                SELECT 'RECEIVED', 'payment_transaction/' || p.payment_id, p.invoice_version_no::text,
                       p.received_at, p.received_amount
                FROM adc.payment_transaction p JOIN adc.treatment_invoice i ON i.invoice_id=p.invoice_id
                JOIN adc.visit v ON v.visit_id=i.visit_id
                WHERE v.clinic_id=? AND p.local_settlement_status='已结算' AND p.transaction_result='模拟成功'
                  AND p.received_at>=? AND p.received_at<?
                ORDER BY metric, fact_id
                """)) {
            for (int first : new int[]{1, 4}) {
                sql.setLong(first, clinic);
                sql.setObject(first + 1, window.start().atOffset(ZoneOffset.UTC));
                sql.setObject(first + 2, window.end().atOffset(ZoneOffset.UTC));
            }
            try (var rows = sql.executeQuery()) {
                while (rows.next()) {
                    facts.add(new QuarterlyPreviewService.Entry(rows.getString(2), rows.getString(3),
                            clinic, rows.getObject(4, OffsetDateTime.class).toInstant(),
                            QuarterlyPreviewService.Metric.valueOf(rows.getString(1)), rows.getBigDecimal(5), null, null));
                }
            }
        }
        return facts;
    }

    private static List<QuarterlyPreviewService.Entry> consumptionFacts(Connection c, long clinic,QuarterlyWindow window) throws SQLException {
        var facts = new java.util.ArrayList<QuarterlyPreviewService.Entry>();
        try (var sql = c.prepareStatement("""
                SELECT x.consumption_id, x.occurred_at, x.consumption_cost
                FROM adc.supply_consumption x JOIN adc.medical_supply_stock s ON s.stock_id=x.stock_id
                WHERE s.clinic_id=? AND x.occurred_at>=? AND x.occurred_at<?
                ORDER BY x.consumption_id
                """)) {
            sql.setLong(1, clinic);
            sql.setObject(2, window.start().atOffset(ZoneOffset.UTC));
            sql.setObject(3, window.end().atOffset(ZoneOffset.UTC));
            try (var rows = sql.executeQuery()) {
                while (rows.next()) {
                    facts.add(new QuarterlyPreviewService.Entry("supply_consumption/" + rows.getLong(1), "immutable",
                            clinic, rows.getObject(2, OffsetDateTime.class).toInstant(),
                            QuarterlyPreviewService.Metric.CONSUMPTION, rows.getBigDecimal(3), null, null));
                }
            }
        }
        return facts;
    }

    private static QuarterlyPreviewService.Entry applyExpenseCorrection(Connection c,QuarterlyPreviewService.Target target,QuarterExpenseCatalog.Expense expense,List<QuarterlyPreviewService.AppliedCorrection> corrections)throws SQLException{
        try(var sql=c.prepareStatement("""
            SELECT h.correction_history_id,h.corrected_value,h.resulting_statistic_id,i.statistic_id
            FROM adc.quarterly_issue_correction_history h JOIN adc.quarterly_data_issue i ON i.issue_id=h.issue_id
            WHERE i.original_data=? AND i.clinic_id=? AND i.correction_status='复核通过' AND h.correction_status='复核通过'
              AND h.checked_at IS NOT NULL AND h.corrected_value=i.corrected_value
            ORDER BY h.corrected_at DESC,h.correction_history_id DESC LIMIT 1
            """)){
            sql.setString(1,expense.marker());sql.setLong(2,expense.clinic());
            try(var rows=sql.executeQuery()){
                if(!rows.next()||!expense.correction().amount().equals(rows.getString(2)))return expenseEntry(expense);
                long history=rows.getLong(1);String value=rows.getString(2),version="reviewed/"+history;
                if(rows.getObject(3)==null){
                    var members=new java.util.HashSet<Long>();try(var scopeSql=c.prepareStatement("SELECT clinic_id FROM adc.quarterly_statistic_clinic WHERE statistic_id=?")){scopeSql.setLong(1,rows.getLong(4));try(var scopeRows=scopeSql.executeQuery()){while(scopeRows.next())members.add(scopeRows.getLong(1));}}
                    if(!members.equals(target.clinics()))return expenseEntry(expense);
                    corrections.add(new QuarterlyPreviewService.AppliedCorrection(history,value,expense.factId(),expense.version(),version,expense.clinic()));
                }
                var proof=AuditDocuments.parse(expense.proof()).deepCopy();((tools.jackson.databind.node.ObjectNode)proof).put("correctionHistoryId",history).put("originalAmount",expense.amount().toPlainString());
                return new QuarterlyPreviewService.Entry(expense.factId(),version,expense.clinic(),expense.at(),QuarterlyPreviewService.Metric.EXPENSE,new BigDecimal(value),null,null,proof.toString());
            }
        }
    }
    private Optional<QuarterExpenseCatalog.Expense> correctionExpense(QuarterlyIssueService.Issue issue){
        try{
            var catalog=QuarterExpenseCatalog.read(expensesPath);
            return catalog.find(issue.original(),issue.clinic()).filter(e->e.correction()!=null)
              .filter(e->issue.sourceVersion().startsWith(VERSION+"/"+catalog.version()+"/review")||e.legacy()&&issue.sourceVersion().startsWith(LEGACY+"/review"));
        }catch(IllegalArgumentException|java.io.IOException unavailable){return Optional.empty();}
    }
    @Bean QuarterlyIssueController.Access quarterlyIssue(){
        var source=new QuarterlyIssueService.Sources(){
            @Override public QuarterlyIssueService.Correction verify(Connection c,QuarterlyIssueService.Target target,QuarterlyIssueService.Issue issue,String evidence,String proposed){
                return correctionExpense(issue).filter(e->e.correction().evidence().equals(evidence)&&e.correction().amount().equals(proposed)).map(e->new QuarterlyIssueService.Correction(proposed,evidence,null,issue.previous())).orElse(null);
            }
            @Override public QuarterlyIssueService.Check recheck(Connection c,QuarterlyIssueService.Target target,QuarterlyIssueService.Issue issue,long history){
                return correctionExpense(issue).filter(e->e.correction().amount().equals(issue.previous())).map(e->new QuarterlyIssueService.Check(true,e.correction().description(),"SYNTHETIC source "+e.manifest()+"/"+e.id()+"/"+e.version())).orElse(null);
            }
        };
        return new QuarterlyIssueController.StaffAccess(tx,bindings(),source,c->next(c,"course_quarterly_correction_id_seq"),CourseQuarterlyConfiguration::audit,clock);
    }

    @Bean QuarterlyCalculationController.Access quarterlyCalculation() {
        return new QuarterlyCalculationController.StaffAccess(tx, bindings(), (c, scope, frozen) ->
                (frozen.version().startsWith(VERSION+"/")||frozen.version().startsWith(LEGACY+"/"))
                        ? Optional.of(new QuarterlyCalculationService.Policy("SYNTHETIC accrual",
                                QuarterlyCalculationService.Revenue.RECEIVABLE, false, true))
                        : Optional.empty(), c -> next(c,"course_quarterly_calculation_id_seq"), CourseQuarterlyConfiguration::audit, clock);
    }

    @Bean QuarterlyFinancialReviewController.Access quarterlyFinancialReview() {
        return new QuarterlyFinancialReviewController.StaffAccess(tx, bindings(), c -> next(c,"course_financial_review_id_seq"),
                CourseQuarterlyConfiguration::audit, clock);
    }
}
