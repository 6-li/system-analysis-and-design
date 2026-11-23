package cn.adc.shared.audit;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import java.time.Clock;
/** Infrastructure facade, no duration configuration business service/controller. */
public final class ExtensionAuditService {
 private final CommandRecovery commands;
 public ExtensionAuditService(JdbcTransactions tx,StaffAuthorization auth,CommandRecovery.SuccessVerifier actualVerifier,Clock clock){commands=new CommandRecovery(tx,new AuditStore(AuditStore.Family.HQ),auth,actualVerifier,clock);}
 public CommandRecovery commands(){return commands;}
}
