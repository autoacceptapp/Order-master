const { readFileSync } = require("node:fs");
const { describe, it, before, after, beforeEach } = require("node:test");
const assert = require("node:assert/strict");
const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const {
  doc,
  getDoc,
  setDoc,
  updateDoc,
  deleteDoc,
  collection,
  getDocs,
  addDoc,
  serverTimestamp,
  Timestamp,
} = require("firebase/firestore");

const PROJECT_ID = "order-master-zero-trust-test";
const USER_ALICE = "alice_uid_123";
const USER_BOB = "bob_uid_456";
const ADMIN_UID = "admin_uid_001";

let testEnv;

describe("Order Master Zero-Trust Firestore Security Rules", () => {
  before(async () => {
    const [host, portStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
    testEnv = await initializeTestEnvironment({
      projectId: PROJECT_ID,
      firestore: {
        host,
        port: Number(portStr),
        rules: readFileSync("firestore.rules", "utf8"),
      },
    });
  });

  after(async () => {
    if (testEnv) await testEnv.cleanup();
  });

  beforeEach(async () => {
    await testEnv.clearFirestore();
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      await setDoc(doc(db, "admins", ADMIN_UID), { active: true });
      await setDoc(doc(db, "app_settings", "global"), {
        isServiceActive: true,
        isPaymentSystemActive: true,
      });
      await setDoc(doc(db, "users", USER_ALICE), {
        firebaseUid: USER_ALICE,
        pointsBalance: 50,
        referralCode: "OM123456",
        hasClaimedReferral: false,
        autoAcceptEnabled: false,
        minFare: 50,
        passExpiryTimestamp: 0,
      });
      await setDoc(doc(db, "users", USER_BOB), {
        firebaseUid: USER_BOB,
        pointsBalance: 120,
        referralCode: "OMBOB789",
        hasClaimedReferral: false,
        autoAcceptEnabled: true,
        minFare: 80,
      });
      await setDoc(doc(db, "received_payments", "426719823451"), {
        utr: "426719823451",
        amount: 49,
        status: "Verified",
        isUsed: false,
        claimedByUserId: "",
      });
      await setDoc(doc(db, "received_payments", "999888777666"), {
        utr: "999888777666",
        amount: 179,
        status: "Verified",
        isUsed: true,
        claimedByUserId: USER_ALICE,
      });
    });
  });

  it("denies unauthenticated access to users, pending_actions, and received_payments", async () => {
    const unauthDb = testEnv.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(unauthDb, "users", USER_ALICE)));
    await assertFails(getDocs(collection(unauthDb, "users")));
    await assertFails(getDoc(doc(unauthDb, "received_payments", "426719823451")));
    await assertFails(
      addDoc(collection(unauthDb, "pending_actions"), {
        userId: USER_ALICE,
        firebaseUid: USER_ALICE,
        actionType: "INIT_PROFILE",
        status: "PENDING",
      })
    );
  });

  it("denies cross-user reads and enforces strict read-only client users/{uid}", async () => {
    const aliceDb = testEnv.authenticatedContext(USER_ALICE).firestore();

    // Alice can read own profile
    const ownSnap = await assertSucceeds(getDoc(doc(aliceDb, "users", USER_ALICE)));
    assert.equal(ownSnap.data().pointsBalance, 50);

    // Alice CANNOT read Bob's profile or list users
    await assertFails(getDoc(doc(aliceDb, "users", USER_BOB)));
    await assertFails(getDocs(collection(aliceDb, "users")));

    // Alice CANNOT mutate own points, pass, banUntil, role, or create new user doc directly
    await assertFails(updateDoc(doc(aliceDb, "users", USER_ALICE), { pointsBalance: 999999 }));
    await assertFails(updateDoc(doc(aliceDb, "users", USER_ALICE), { passExpiryTimestamp: 9999999999999 }));
    await assertFails(updateDoc(doc(aliceDb, "users", USER_ALICE), { banUntil: null }));
    await assertFails(updateDoc(doc(aliceDb, "users", USER_ALICE), { role: "admin" }));
    await assertFails(deleteDoc(doc(aliceDb, "users", USER_ALICE)));
    await assertFails(
      setDoc(doc(aliceDb, "users", "hacker_uid_new"), {
        firebaseUid: "hacker_uid_new",
        pointsBalance: 9999,
      })
    );
  });

  it("blocks ADD_POINTS, UID spoofing, COMPLETED pre-marking, and extra fields in pending_actions", async () => {
    const aliceDb = testEnv.authenticatedContext(USER_ALICE).firestore();

    // Blocked arbitrary ADD_POINTS
    await assertFails(
      addDoc(collection(aliceDb, "pending_actions"), {
        userId: USER_ALICE,
        firebaseUid: USER_ALICE,
        actionType: "ADD_POINTS",
        status: "PENDING",
        amount: 5000,
      })
    );

    // Blocked REWARD_AD_WATCHED with custom amount
    await assertFails(
      addDoc(collection(aliceDb, "pending_actions"), {
        userId: USER_ALICE,
        firebaseUid: USER_ALICE,
        actionType: "REWARD_AD_WATCHED",
        status: "PENDING",
        amount: 500,
      })
    );

    // Blocked pre-marked COMPLETED status
    await assertFails(
      addDoc(collection(aliceDb, "pending_actions"), {
        userId: USER_ALICE,
        firebaseUid: USER_ALICE,
        actionType: "CLAIM_REFERRAL",
        status: "COMPLETED",
        referralCode: "OMBOB789",
      })
    );

    // Blocked impersonating Bob's UID
    await assertFails(
      addDoc(collection(aliceDb, "pending_actions"), {
        userId: USER_BOB,
        firebaseUid: USER_BOB,
        actionType: "CLAIM_REFERRAL",
        status: "PENDING",
        referralCode: "OM123456",
      })
    );

    // Blocked undeclared/ghost field
    await assertFails(
      addDoc(collection(aliceDb, "pending_actions"), {
        userId: USER_ALICE,
        firebaseUid: USER_ALICE,
        actionType: "CLAIM_REFERRAL",
        status: "PENDING",
        referralCode: "OMBOB789",
        hackedFlag: true,
      })
    );
  });

  it("allows valid client PENDING action creation and forbids client status updates", async () => {
    const aliceDb = testEnv.authenticatedContext(USER_ALICE).firestore();

    const ref = await assertSucceeds(
      addDoc(collection(aliceDb, "pending_actions"), {
        userId: USER_ALICE,
        firebaseUid: USER_ALICE,
        actionType: "CLAIM_REFERRAL",
        status: "PENDING",
        referralCode: "OMBOB789",
        createdAt: serverTimestamp(),
      })
    );

    // Alice can read own action, CANNOT mark it COMPLETED
    await assertSucceeds(getDoc(ref));
    await assertFails(updateDoc(ref, { status: "COMPLETED", newBalance: 9999 }));
  });

  it("locks received_payments and protects pending_verifications from spoofing", async () => {
    const aliceDb = testEnv.authenticatedContext(USER_ALICE).firestore();
    const bobDb = testEnv.authenticatedContext(USER_BOB).firestore();

    // Unclaimed received_payments cannot be read or modified by clients
    await assertFails(getDoc(doc(aliceDb, "received_payments", "426719823451")));
    await assertFails(getDocs(collection(aliceDb, "received_payments")));
    await assertFails(
      setDoc(doc(aliceDb, "received_payments", "111222333444"), {
        utr: "111222333444",
        amount: 179,
        status: "Verified",
        isUsed: false,
      })
    );

    // Alice can read only payment she owns
    await assertSucceeds(getDoc(doc(aliceDb, "received_payments", "999888777666")));
    await assertFails(getDoc(doc(bobDb, "received_payments", "999888777666")));

    // Alice can create a PENDING verification for herself, NOT pre-Verified
    await assertFails(
      setDoc(doc(aliceDb, "pending_verifications", "555666777888"), {
        utr: "555666777888",
        status: "Verified",
        isUsed: false,
        userId: USER_ALICE,
      })
    );

    await assertSucceeds(
      setDoc(doc(aliceDb, "pending_verifications", "555666777888"), {
        utr: "555666777888",
        status: "PENDING",
        isUsed: false,
        userId: USER_ALICE,
        firebaseUid: USER_ALICE,
        amount: 49,
        timestamp: serverTimestamp(),
      })
    );

    // Alice CANNOT update her pending_verification to Verified
    await assertFails(
      updateDoc(doc(aliceDb, "pending_verifications", "555666777888"), {
        status: "Verified",
      })
    );
  });

  it("blocks client writes to app_settings and admins while permitting Admin overrides", async () => {
    const aliceDb = testEnv.authenticatedContext(USER_ALICE).firestore();
    const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();

    await assertSucceeds(getDoc(doc(aliceDb, "app_settings", "global")));
    await assertFails(
      setDoc(doc(aliceDb, "app_settings", "global"), { isServiceActive: false })
    );
    await assertFails(
      setDoc(doc(aliceDb, "admins", USER_ALICE), { active: true })
    );

    // Admin succeeds on privileged operations
    await assertSucceeds(
      updateDoc(doc(adminDb, "users", USER_ALICE), {
        pointsBalance: 250,
        passExpiryTimestamp: Timestamp.now().toMillis() + 86400000,
      })
    );
    await assertSucceeds(
      setDoc(doc(adminDb, "app_settings", "global"), { isServiceActive: false })
    );
  });
});
