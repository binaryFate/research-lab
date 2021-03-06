package how.monero.hodl.ringSignature;

import how.monero.hodl.crypto.PointPair;
import how.monero.hodl.crypto.Scalar;
import how.monero.hodl.cursor.BootleRuffingCursor;
import how.monero.hodl.util.VarInt;
import org.nem.core.crypto.ed25519.arithmetic.*;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import static how.monero.hodl.crypto.CryptoUtil.*;
import static how.monero.hodl.crypto.HashToPoint.hashToPoint;
import static how.monero.hodl.crypto.Scalar.bigIntegerArrayToScalarArray;
import static how.monero.hodl.crypto.Scalar.randomScalar;
import static how.monero.hodl.util.ByteUtil.*;


public class BootleRuffing {

  public static class SK {
    public Scalar r;
    public Scalar r1;
    public SK(Scalar r, Scalar r1) {
      this.r = r; this.r1 = r1;
    }

    @Override
    public String toString() {
      return "(r: " + bytesToHex(r) + ", r1: " + bytesToHex(r1) + ")";
    }
  }

  public static KeyGenResult KEYGEN() {
    SK sk = new SK(randomScalar(), randomScalar());
    Ed25519GroupElement ki = G.scalarMultiply(sk.r1);
    PointPair pk = ENCeg(ki, sk.r);
    return new KeyGenResult(sk, ki, pk);
  }
  public static class KeyGenResult {
    public SK sk;
    public Ed25519GroupElement ki;
    public PointPair pk = null;
    public KeyGenResult(SK sk, Ed25519GroupElement ki, PointPair pk) {
      this.sk = sk; this.ki = ki; this.pk = pk;
    }

    @Override
    public String toString() {
      return "sk: " + sk.toString() + ", ki: " + bytesToHex(ki.encode().getRaw()) + ", pk: " + (pk==null ? "(no pk)" : "pk: " + pk);
    }
  }

  public static class F {
    public Ed25519GroupElement[] ki;
    public PointPair[][] pk;
    public Ed25519GroupElement[] co;
    public Ed25519GroupElement co1;
    byte[] M;
    public F(Ed25519GroupElement[] ki, PointPair[][] pk, Ed25519GroupElement[] co, Ed25519GroupElement co1, byte[] M) {
      this.ki = ki; this.pk = pk; this.co = co; this.co1 = co1; this.M = M;
    }
    byte[] toBytes() {
      byte[] r = new byte[0];
      for(int i=0; i<ki.length; i++) r = concat(r, ki[i].encode().getRaw());
      for(int i=0; i<pk.length; i++) for(int j=0; j<pk[i].length; j++) r = concat(r, pk[i][j].toBytes());
      for(int i=0; i<co.length; i++) r = concat(r, co[i].encode().getRaw());
      r = concat(r, co1.encode().getRaw());
      r = concat(r, M);
      return r;
    }
  }

  public static SpendSignature SPEND(SpendParams sp) {

    int iAsterisk = sp.iAsterisk;
    PointPair[][] pk = sp.pk;
    SK[] sk = sp.sk;
    Ed25519GroupElement[] ki = sp.ki;
    Ed25519GroupElement[] co = sp.co;
    byte[] M = sp.M;
    Scalar s = sp.s;
    int decompositionBase = sp.decompositionBase;
    int decompositionExponent = sp.decompositionExponent;

    Ed25519GroupElement co1 = G.scalarMultiply(s);
    F f = new F(ki, pk, co, co1, M);
    SubResult cf1 = SUB(f);
    Scalar s1 = s;
    for(int i=0; i<sk.length; i++) s1 = s1.add(sk[i].r.mul(cf1.f[i]));
    Proof2 sigma1 = PROVE2(cf1.c, iAsterisk, s1, pk.length, decompositionBase, decompositionExponent);

    Scalar[] r1 = new Scalar[sk.length];
    for(int i=0; i<r1.length; i++) r1[i] = sk[i].r1;

    Multisignature.Signature sigma2 = Multisignature.sign(concat(sigma1.toBytes(decompositionBase, decompositionExponent), f.toBytes()), r1, null);
    return new SpendSignature(decompositionBase, decompositionExponent, co1, sigma1, sigma2);
  }

  public static class SpendSignature {
    public int decompositionBase;
    public int decompositionExponent;
    public Ed25519GroupElement co1;
    public Proof2 sigma1;
    Multisignature.Signature sigma2;
    public SpendSignature(int decompositionBase, int decompositionExponent, Ed25519GroupElement co1, Proof2 sigma1, Multisignature.Signature sigma2) {
      this.decompositionBase = decompositionBase; this.decompositionExponent = decompositionExponent; this.co1 = co1; this.sigma1 = sigma1; this.sigma2 = sigma2;
    }
    public byte[] toBytes() {
      byte[] result;
      result = concat(VarInt.writeVarInt(decompositionBase), VarInt.writeVarInt(decompositionExponent));
      result = concat(result, co1.encode().getRaw(), sigma1.toBytes(decompositionBase, decompositionExponent), sigma2.toBytes());
      return result;
    }
    public static SpendSignature fromBytes(byte[] a) {
      BootleRuffingCursor cursor = new BootleRuffingCursor(a);
      int decompositionBase = (int) cursor.readVarInt();
      int decompositionExponent = (int) cursor.readVarInt();
      return new SpendSignature(decompositionBase, decompositionExponent,
        cursor.readGroupElement(),
        new Proof2(
          new Proof1(cursor.readGroupElement(), cursor.readGroupElement(), cursor.readGroupElement(),
            cursor.readScalar2DArray(decompositionExponent, decompositionBase-1), cursor.readScalar(), cursor.readScalar(), null),
          cursor.readGroupElement(),
          cursor.readPointPairArray(decompositionExponent), cursor.readScalar()),
        new Multisignature.Signature(cursor.readGroupElement(), cursor.readScalar()));
    }
  }

  public static SubResult SUB(F fin) {
    int L = fin.pk.length; // inputs
    int N = fin.pk[0].length; // ring size
    PointPair[] pkz = new PointPair[L];
    Scalar[] f = new Scalar[L];
    for(int j=0; j<L; j++) {
      pkz[j] = new PointPair(fin.ki[j], Ed25519Group.ZERO_P3);
      f[j] = hashToScalar(concat(fin.ki[j].encode().getRaw(), fin.toBytes(), longToLittleEndianUint32ByteArray(j)));
    }
    PointPair[] c = new PointPair[N];
    for(int i=0; i<N; i++) {
      c[i] = new PointPair(fin.co[i], fin.co1);
      for(int j=0; j<L; j++) {
        c[i] = c[i].add( (fin.pk[j][i].subtract(pkz[j])).multiply(f[j]) );
      }
    }
    return new SubResult(c, f);
  }
  public static class SubResult {
    public PointPair[] c;
    public Scalar[] f;
    public SubResult(PointPair[] c, Scalar[] f) {
      this.c = c; this.f = f;
    }
  }

  public static Proof1 PROVE1(Scalar[][] b, Scalar r) {

    int m = b.length; // decompositionExponent
    int n = b[0].length; // decompositionBase

    Scalar rA = randomScalar();
    Scalar rC = randomScalar();
    Scalar rD = randomScalar();

    Scalar[][] a = new Scalar[m][n];
    for(int j=0; j<m; j++) {
      for(int i=1; i<n; i++) {
        a[j][i] = randomScalar();
      }
    }

    for(int j=0; j<m; j++) {
      a[j][0] = Scalar.ZERO;
      for(int i=1; i<n; i++) {
        a[j][0] = a[j][0].sub(a[j][i]);
      }
    }

    Ed25519GroupElement A = COMb(a, rA);

    Scalar[][] c = new Scalar[m][n];
    Scalar[][] d = new Scalar[m][n];
    for(int j=0; j<m; j++) {
      for(int i=0; i<n; i++) {
        c[j][i] = a[j][i].mul(Scalar.ONE.sub(b[j][i].mul(Scalar.TWO)));
        d[j][i] = a[j][i].sq().mul(Scalar.MINUS_ONE);
      }
    }

    Ed25519GroupElement C = COMb(c, rC);
    Ed25519GroupElement D = COMb(d, rD);

    Scalar x = hashToScalar(concat(A.encode().getRaw(), C.encode().getRaw(), D.encode().getRaw()));

    Scalar[][] f = new Scalar[m][n];
    for(int j=0; j<m; j++) {
      for(int i=0; i<n; i++) {
        f[j][i] = b[j][i].mul(x).add(a[j][i]);
      }
    }

    Scalar[][] fTrimmed = new Scalar[m][n-1];
    for(int j=0; j<m; j++) {
      for(int i=1; i<n; i++) {
        fTrimmed[j][i-1] = f[j][i];
      }
    }

    Scalar zA = r.mul(x).add(rA);
    Scalar zC = rC.mul(x).add(rD);

    return new Proof1(A, C, D, fTrimmed, zA, zC, a);
  }

  public static class Proof1 {
    public Ed25519GroupElement A;
    public Ed25519GroupElement C;
    public Ed25519GroupElement D;
    private Scalar[][] fTrimmed;
    private Scalar zA;
    private Scalar zC;
    public transient Scalar[][] a;

    private Proof1(Ed25519GroupElement A, Ed25519GroupElement C, Ed25519GroupElement D, Scalar[][] fTrimmed,
                  Scalar zA, Scalar zC, Scalar[][] a) {
      this.A = A; this.C = C; this.D = D; this.fTrimmed = fTrimmed; this.zA = zA; this.zC = zC; this.a = a;
    }
    private byte[] toBytes(int decompositionBase, int decompositionExponent) {
      byte[] result = concat(A.encode().getRaw(), C.encode().getRaw(), D.encode().getRaw());
      for(int j=0; j<decompositionExponent; j++) {
        for(int i=0; i<decompositionBase-1; i++) {
          result = concat(result, fTrimmed[j][i].bytes);
        }
      }
      result = concat(result, zA.bytes, zC.bytes);
      return result;
    }
  }

  public static Proof2 PROVE2(PointPair[] co, int iAsterisk, Scalar r, int inputs, int decompositionBase, int decompositionExponent) {

    int ringSize = (int) Math.pow(decompositionBase, decompositionExponent);

    Scalar[] u = new Scalar[decompositionExponent];
    for(int k=0; k<decompositionExponent; k++) u[k] = randomScalar();

    Scalar rB = randomScalar();

    int[] iAsteriskSequence = nAryDecompose(decompositionBase, iAsterisk, decompositionExponent);

    Scalar[][] d = new Scalar[decompositionExponent][decompositionBase];
    for(int j=0; j<decompositionExponent; j++) {
      for(int i=0; i<decompositionBase; i++) {
        d[j][i] = Scalar.intToScalar(delta(iAsteriskSequence[j], i));
      }
    }

    Ed25519GroupElement B = COMb(d, rB);

    Proof1 P = PROVE1(d, rB);

    Scalar[][] coefs = COEFS(P.a, iAsterisk);

    PointPair[] G = new PointPair[decompositionExponent];

    for(int k=0; k<decompositionExponent; k++) {
      G[k] = ENCeg(Ed25519Group.ZERO_P3, u[k]);
      for (int i = 0; i < ringSize; i++) {
        G[k] = G[k].add(co[i].multiply(coefs[i][k]));
      }
    }

    byte[] bytes = concat(P.A.encode().getRaw(), P.C.encode().getRaw(), P.D.encode().getRaw());
    Scalar x1 = hashToScalar(bytes);

    Scalar z = r.mul(x1.pow(decompositionExponent));
    for(int i=decompositionExponent-1; i>=0; i--) {
      z = z.sub(u[i].mul(x1.pow(i)));
    }
    return new Proof2(P, B, G, z);
  }


  public static class Proof2 {
    Proof1 P;
    public Ed25519GroupElement B;
    public PointPair[] G;
    public Scalar z;

    private Proof2(Proof1 P, Ed25519GroupElement B, PointPair[] G, Scalar z) {
      this.P = P;
      this.B = B;
      this.G = G;
      this.z = z;
    }

    private byte[] toBytes(int decompositionBase, int decompositionExponent) {
      byte[] bytes;
      bytes = concat(P.toBytes(decompositionBase, decompositionExponent), B.encode().getRaw());
      for(PointPair g : G) bytes = concat(bytes, g.toBytes());
      bytes = concat(bytes, z.bytes);
      return bytes;
    }

  }

  private static int delta(int j, int i) {
    return j==i ? 1 : 0;
  }
  private static int intPow(int a, int b) {
    return (int) Math.round(Math.pow(a, b));
  }
  public static int[] nAryDecompose(int base, int n, int decompositionExponent) {
    int[] r = new int[decompositionExponent];
    for(int i=decompositionExponent-1; i>=0; i--) {
      int basePow = intPow(base, i);
      r[i] = n / basePow;
      n-=basePow*r[i];
    }
    return r;
  }

  public static boolean VALID1(Ed25519GroupElement B, Proof1 P) {
    boolean abcdOnCurve =
      P.A.satisfiesCurveEquation()
      && B.satisfiesCurveEquation()
      && P.C.satisfiesCurveEquation()
      && P.D.satisfiesCurveEquation();
    if(!abcdOnCurve) {
      System.out.println("VALID1: ABCD not on curve");
      return false;
    }

    int m = P.fTrimmed.length;
    int n = P.fTrimmed[0].length + 1;

    Scalar[][] f = new Scalar[m][n];
    for(int j=0; j<m; j++) {
      for(int i=1; i<n; i++) {
        f[j][i] = P.fTrimmed[j][i-1];
      }
    }

    Scalar x = hashToScalar(concat(P.A.encode().getRaw(), P.C.encode().getRaw(), P.D.encode().getRaw()));

    for(int j=0; j<m; j++) {
      f[j][0] = x;
      for(int i=1; i<n; i++) {
        f[j][0] = f[j][0].sub(f[j][i]);
      }
    }

    Scalar[][] f1 = new Scalar[m][n];
    for(int j=0; j<m; j++) {
      for(int i=0; i<n; i++) {
        f1[j][i] = f[j][i].mul(x.sub(f[j][i]));
      }
    }

    for(int j=0; j<m; j++) {
      Scalar colSum = x;
      for(int i=1; i<n; i++) {
        colSum = colSum.sub(f[j][i]);
      }
      if(!f[j][0].equals(colSum)) {
        System.out.println("VALID1: FAILED For each j=0, ..., m-1, f[j][0] == x-f[j][1]-f[j][2]- ... -f[j][n-1]");
        return false;
      }
    }

    if(!B.toP3().scalarMultiply(x).toP3().add(P.A.toP3().toCached()).equals(COMb(f, P.zA))) {
      System.out.println("VALID1: FAILED xB + A == COMp(f[0][0], ..., f[m-1][n-1]; z[A])");
      return false;
    }
    if(!P.C.toP3().scalarMultiply(x).toP3().add(P.D.toP3().toCached()).equals(COMb(f1, P.zC))) {
      System.out.println("VALID1: FAILED xC + D == COMp(f'[0][0], ..., f'[m-1][n-1]; z[C])");
      return false;
    }

    return true;

  }

  public static boolean VALID2(int decompositionBase, Proof2 P1, PointPair[] co) {

    boolean abcdOnCurve =
      P1.P.A.satisfiesCurveEquation()
      && P1.B.satisfiesCurveEquation()
      && P1.P.C.satisfiesCurveEquation()
      && P1.P.D.satisfiesCurveEquation();
    if(!abcdOnCurve) {
      System.out.println("VALID2: FAILED: ABCD not on curve");
      return false;
    }

    if(!VALID1(P1.B, P1.P)) {
      System.out.println("VALID2: FAILED: VALID1 failed");
      return false;
    }

    Scalar x1 = hashToScalar(concat(P1.P.A.encode().getRaw(), P1.P.C.encode().getRaw(), P1.P.D.encode().getRaw()));

    int decompositionExponent = P1.P.fTrimmed.length;
    Scalar[][] f = new Scalar[decompositionExponent][decompositionBase];
    for(int j=0; j<decompositionExponent; j++) {
      for(int i=1; i<decompositionBase; i++) {
        f[j][i] = P1.P.fTrimmed[j][i-1];
      }
    }

    int ringSize = (int) Math.pow(decompositionBase, decompositionExponent);

    PointPair c = ENCeg(Ed25519Group.ZERO_P3, P1.z);

    Scalar x = hashToScalar(concat(P1.P.A.encode().getRaw(), P1.P.C.encode().getRaw(), P1.P.D.encode().getRaw()));
    for(int j=0; j<decompositionExponent; j++) {
      f[j][0] = x;
      for(int i=1; i<decompositionBase; i++) {
        f[j][0] = f[j][0].sub(f[j][i]);
      }
    }

    Scalar[] g = new Scalar[ringSize];
    g[0] = f[0][0];
    for(int j=1; j<decompositionExponent; j++) {
      g[0] = g[0].mul(f[j][0]);
    }

    PointPair c1 = co[0].multiply(g[0]);
    for(int i=1; i<ringSize; i++) {
      int[] iSequence = nAryDecompose(decompositionBase, i, decompositionExponent);
      g[i] = f[0][iSequence[0]];
      for(int j=1; j<decompositionExponent; j++) {
        g[i] = g[i].mul(f[j][iSequence[j]]);
      }
      c1 = c1.add(co[i].multiply(g[i]));
    }

    for(int k=0; k<decompositionExponent; k++) {
      c1 = c1.subtract(P1.G[k].multiply(x1.pow(k)));
    }
    

    boolean result = c1.equals(c);
    if(!result) {
      System.out.println("VALID2: FAILED: c' != c");
      System.out.println("c:  (" + bytesToHex(c.P1.encode().getRaw()) + ", " + bytesToHex(c.P2.encode().getRaw()));
      System.out.println("c': (" + bytesToHex(c1.P1.encode().getRaw()) + ", " + bytesToHex(c1.P2.encode().getRaw()));
    }
    return result;

  }

  public static boolean VER(Ed25519GroupElement[] ki, PointPair[][] pk, Ed25519GroupElement[] co, Ed25519GroupElement co1, byte[] M, SpendSignature spendSignature) {

    F f = new F(ki, pk, co, co1, M);

    SubResult cf1 = SUB(f);

    if(!Multisignature.verify(concat(spendSignature.sigma1.toBytes(spendSignature.decompositionBase, spendSignature.decompositionExponent), f.toBytes()), ki, spendSignature.sigma2)) {
      System.out.println("Multisignature.verify failed");
      return false;
    }

    if(!VALID2(spendSignature.decompositionBase, spendSignature.sigma1, cf1.c)) {
      System.out.println("VALID2 failed");
      return false;
    }

    return true;
  }

  public static class Output {
    public SK sk;
    public Ed25519GroupElement ki;
    public PointPair pk;

    public Scalar mask;
    public Ed25519GroupElement co;
    public BigInteger amount;
    public static Output genRandomOutput(BigInteger amount) {
      Output o = new Output();
      KeyGenResult keyGenResult = KEYGEN();
      o.sk = keyGenResult.sk;
      o.pk = keyGenResult.pk;
      o.ki = keyGenResult.ki;
      o.amount = amount;
      o.mask = randomScalar();
      o.co = COMp(new Scalar(o.amount), o.mask);
      return o;
    }
  }

  public static Scalar[][] COEFS(Scalar[][] a, int iAsterisk) {
    int decompositionBase = a[0].length; // n
    int decompositionExponent = a.length; // m
    int ringSize = (int) Math.pow(decompositionBase, decompositionExponent); // N = n^m

    int[] iAsteriskSequence = nAryDecompose(decompositionBase, iAsterisk, decompositionExponent);

    Scalar[][] coefList = new Scalar[ringSize][decompositionExponent];

    for(int k=0; k<ringSize; k++) {
      int[] kSequence = nAryDecompose(decompositionBase, k, decompositionExponent);
      coefList[k] = new Scalar[]{
        a[0][kSequence[0]],
        Scalar.intToScalar(delta(iAsteriskSequence[0], kSequence[0]))
      };

      for(int j=1; j<decompositionExponent; j++) {
        coefList[k] = COEFPROD(coefList[k], new Scalar[]{
          a[j][kSequence[j]],
          Scalar.intToScalar(delta(iAsteriskSequence[j], kSequence[j]))
        });
      }
    }
    for(int k=0; k<ringSize; k++) {
     coefList[k] = trimScalarArray(coefList[k], decompositionExponent, decompositionExponent);
    }
    return coefList;
  }

  private static Scalar[] trimScalarArray(Scalar[] a, int len, int indexWhere1ValueCanBeTrimmed) {
    Scalar[] r = new Scalar[len];
    for(int i=0; i<a.length; i++) {
      if(i<len) r[i] = a[i];
      else {
        if(i==indexWhere1ValueCanBeTrimmed) {
          if(!(a[i].equals(Scalar.ZERO) || a[i].equals(Scalar.ONE))) throw new RuntimeException("Attempt to trim non-zero or non-one in column " + i + ": value: " + a[i].toBigInteger());
        }
        else {
          if(!(a[i].equals(Scalar.ZERO))) throw new RuntimeException("Attempt to trim non-zero in column " + i + ": value: " + a[i].toBigInteger());
        }
      }
    }
    return r;
  }

  public static Scalar[] COEFPROD(Scalar[] c, Scalar[] d) {
    int maxLen = Math.max(c.length, d.length);
    int resultLen = 2*maxLen-1;
    BigInteger[] result = new BigInteger[resultLen];

    for (int i = 0; i<resultLen; i++) result[i] = BigInteger.ZERO;
    for (int i=0; i<maxLen; i++)
    {
      for (int j=0; j<maxLen; j++) {
        result[i+j] = result[i+j].add(getBigIntegerAtArrayIndex(c, i).multiply(getBigIntegerAtArrayIndex(d, j)));
      }
    }
    return bigIntegerArrayToScalarArray(result);
  }

  private static BigInteger getBigIntegerAtArrayIndex(Scalar[] a, int index) {
    if(index>=a.length) return BigInteger.ZERO;
    else return a[index].toBigInteger();
  }

}
