interface ResultsPanelProps {
  results: Record<string, Array<Record<string, unknown>>>;
}

export function ResultsPanel({ results }: ResultsPanelProps) {
  const entityTypes = Object.keys(results);

  if (entityTypes.length === 0) {
    return null;
  }

  return (
    <div>
      {entityTypes.map((entityType) => (
        <div key={entityType} style={{ marginBottom: '24px' }}>
          <h3 style={{ textTransform: 'capitalize', borderBottom: '2px solid #0073bb', paddingBottom: '4px' }}>
            {entityType} ({(results[entityType] ?? []).length})
          </h3>
          <div style={{ display: 'grid', gap: '12px' }}>
            {(results[entityType] ?? []).map((result, index) => (
              <ResultCard key={index} result={result} />
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}

function ResultCard({ result }: { result: Record<string, unknown> }) {
  return (
    <div style={{ border: '1px solid #e0e0e0', borderRadius: '8px', padding: '12px', backgroundColor: '#fafafa' }}>
      {Object.entries(result).map(([key, value]) => (
        <div key={key} style={{ display: 'flex', marginBottom: '4px' }}>
          <span style={{ fontWeight: 'bold', minWidth: '140px', color: '#555' }}>{key}:</span>
          <span style={{ color: value === '****' ? '#cc0000' : '#333', fontStyle: value === '****' ? 'italic' : 'normal' }}>
            {String(value)}
          </span>
        </div>
      ))}
    </div>
  );
}
