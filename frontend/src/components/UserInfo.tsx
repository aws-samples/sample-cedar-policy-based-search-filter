import { useAuth } from '../context/AuthContext';

export function UserInfo() {
  const { user, logout } = useAuth();

  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px 0', borderBottom: '1px solid #eee', marginBottom: '20px' }}>
      <div>
        <span style={{ fontWeight: 'bold' }}>{user?.username}</span>
        <span style={{ marginLeft: '8px', padding: '2px 8px', backgroundColor: '#e8f4fd', borderRadius: '4px', fontSize: '14px' }}>
          {user?.role}
        </span>
      </div>
      <button
        onClick={logout}
        style={{ padding: '6px 12px', backgroundColor: '#f5f5f5', border: '1px solid #ccc', borderRadius: '4px', cursor: 'pointer' }}
      >
        Log Out
      </button>
    </div>
  );
}
