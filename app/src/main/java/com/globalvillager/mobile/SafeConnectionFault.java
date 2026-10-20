package com.globalvillager.mobile;
import java.util.*;
/** Only fixed state/error categories leave this formatter; never raw exception messages. */
public final class SafeConnectionFault {
 private SafeConnectionFault(){}
 public static String summarize(String phase,String kind,String detail){
  Set<String> stages=Set.of("checking","selecting","provisioning","starting","verifying","connected","blocked","error","stopping","disconnected");
  String stage=stages.contains(phase==null?"":phase)?phase:"unknown";
  String error=kind!=null&&kind.matches("[A-Za-z_][A-Za-z0-9_]{0,59}")?kind:"Unknown";
  String text=detail==null?"":detail.toLowerCase(Locale.ROOT);List<String> reasons=new ArrayList<>();
  for(String word:new String[]{"x509","certificate","timeout","deadline","refused","no such host","eof","reset","tls","configuration","address already","permission"})if(text.contains(word))reasons.add(word);
  return "stage="+stage+" error="+error+" reasons="+String.join(",",reasons);
 }
}
