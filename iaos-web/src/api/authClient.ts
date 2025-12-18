import { loginMockUser, registerMockUser } from "@/auth/mockUsers";
import { setUserSession, type UserSession, type UserType } from "@/auth/userAuth";
import { httpClient } from "./httpClient";

type RegisterUserInput = {
  email: string;
  password: string;
  type: UserType;
  rememberMe: boolean;
};

type LoginUserInput = {
  email: string;
  password: string;
  rememberMe: boolean;
};

export async function registerUser(input: RegisterUserInput): Promise<UserSession> {
  const email = input.email.trim();
  if (!email || !input.password) throw new Error("Preencha e-mail e senha.");

  // Futuro backend: return await httpClient.post("/auth/register", { ... })
  void httpClient;

  registerMockUser({ email, password: input.password, type: input.type });
  return setUserSession({ email, type: input.type, rememberMe: input.rememberMe });
}

export async function loginUser(input: LoginUserInput): Promise<UserSession> {
  const email = input.email.trim();
  if (!email || !input.password) throw new Error("Preencha e-mail e senha.");

  // Futuro backend: return await httpClient.post("/auth/login", { ... })
  void httpClient;

  const user = loginMockUser({ email, password: input.password });

  return setUserSession({
    email: user.email,
    type: user.type || "personal",
    rememberMe: input.rememberMe,
  });
}
