export interface CurrentUser {
  id: number;
  username: string;
  displayName: string;
  roles: string[];
  permissions: string[];
}

/** GET /api/auth/state. The portal leaves out `user` when nobody is signed in. */
export interface AuthStateResponse {
  setupRequired: boolean;
  user?: CurrentUser;
}
