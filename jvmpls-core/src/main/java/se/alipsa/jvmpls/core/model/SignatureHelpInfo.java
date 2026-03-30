package se.alipsa.jvmpls.core.model;

import java.util.List;

public final class SignatureHelpInfo {
  private final List<CallableInfo> signatures;
  private final int activeSignature;
  private final int activeParameter;

  public SignatureHelpInfo(
      List<CallableInfo> signatures, int activeSignature, int activeParameter) {
    this.signatures = signatures == null ? List.of() : List.copyOf(signatures);
    this.activeSignature = activeSignature;
    this.activeParameter = activeParameter;
  }

  public List<CallableInfo> getSignatures() {
    return List.copyOf(signatures);
  }

  public int getActiveSignature() {
    return activeSignature;
  }

  public int getActiveParameter() {
    return activeParameter;
  }
}
