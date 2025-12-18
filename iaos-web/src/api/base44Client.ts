type EntityApi = {
  list: (sort?: string, limit?: number) => Promise<any[]>;
  filter: (criteria?: Record<string, any>, sort?: string, limit?: number) => Promise<any[]>;
  create: (data: any) => Promise<any>;
  update: (id: string, data: any) => Promise<any>;
  delete: (id: string) => Promise<void>;
};

function createEntityApi(entityName: string): EntityApi {
  return {
    async list() {
      console.warn(`[base44 mock] ${entityName}.list called`);
      return [];
    },
    async filter() {
      console.warn(`[base44 mock] ${entityName}.filter called`);
      return [];
    },
    async create(data: any) {
      console.warn(`[base44 mock] ${entityName}.create called`);
      return { id: crypto.randomUUID?.() ?? "mock-id", ...data };
    },
    async update(id: string, data: any) {
      console.warn(`[base44 mock] ${entityName}.update called`);
      return { id, ...data };
    },
    async delete() {
      console.warn(`[base44 mock] ${entityName}.delete called`);
    },
  };
}

export const base44 = {
  auth: {
    async me() {
      console.warn("[base44 mock] auth.me called");
      return null;
    },
    async updateMe(data: any) {
      console.warn("[base44 mock] auth.updateMe called");
      return data;
    },
    logout() {
      console.warn("[base44 mock] auth.logout called");
    },
    redirectToLogin() {
      console.warn("[base44 mock] auth.redirectToLogin called");
      window.location.href = "/";
    },
  },
  integrations: {
    Core: {
      async UploadFile({ file }: { file: File }) {
        console.warn("[base44 mock] integrations.Core.UploadFile called");
        return { url: "", fileName: file?.name ?? "" };
      },
    },
  },
  entities: {
    Store: createEntityApi("Store"),
    Product: createEntityApi("Product"),
    StakingPool: createEntityApi("StakingPool"),
    Wallet: createEntityApi("Wallet"),
    ChatConversation: createEntityApi("ChatConversation"),
    ChatMessage: createEntityApi("ChatMessage"),
    Sale: createEntityApi("Sale"),
    QRPayment: createEntityApi("QRPayment"),
    Transaction: createEntityApi("Transaction"),
    Review: createEntityApi("Review"),
    Order: createEntityApi("Order"),
    TradePosition: createEntityApi("TradePosition"),
  },
};

